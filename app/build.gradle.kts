import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

fun getGitSha(): String {
    return try {
        val shaProcess = ProcessBuilder("git", "rev-parse", "HEAD").start()
        val sha = shaProcess.inputStream.bufferedReader().readText().trim()
        val statusProcess = ProcessBuilder("git", "status", "--porcelain").start()
        val status = statusProcess.inputStream.bufferedReader().readText().trim()
        if (status.isNotEmpty()) "$sha-dirty" else sha
    } catch (_: Exception) {
        "unknown"
    }
}

// ---- Privacy gate: no merged manifest may contain android.permission.INTERNET (PRIVACY.md, README) ----
abstract class VerifyNoInternetPermission : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    @TaskAction
    fun check() {
        val text = mergedManifest.get().asFile.readText()
        if (Regex("""android:name\s*=\s*"android\.permission\.INTERNET"""").containsMatchIn(text)) {
            throw GradleException("android.permission.INTERNET found in the merged ${variantName.get()} manifest: LocalSeek must not request network access.")
        }
    }
}

val verifyNoInternetPermission = tasks.register("verifyNoInternetPermission") {
    group = "verification"
    description = "Fails if any variant's merged manifest requests android.permission.INTERNET."
}

androidComponents {
    onVariants { variant ->
        val perVariant = tasks.register<VerifyNoInternetPermission>("verifyNoInternetPermission${variant.name.replaceFirstChar { it.uppercase() }}") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            variantName.set(variant.name)
        }
        verifyNoInternetPermission.configure { dependsOn(perVariant) }
    }
}

tasks.configureEach {
    if (name in setOf("assembleDebug", "assembleRelease", "bundleDebug", "bundleRelease")) dependsOn(verifyNoInternetPermission)
}

// THIRD_PARTY_NOTICES.md is the single source for the in-app licenses page: copy it into a generated
// res/raw directory at build time (file name must be a valid resource name).
val copyNoticesToRaw = tasks.register<Copy>("copyNoticesToRaw") {
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) { rename { "third_party_notices.md" } }
    into(layout.buildDirectory.dir("generated/notices/res/raw"))
}
tasks.named("preBuild") { dependsOn(copyNoticesToRaw) }

val keystoreProps = Properties().also { props ->
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { props.load(it) }
}

android {
    namespace = "com.augt.localseek"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.augt.localseek"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_SHA", "\"${getGitSha()}\"")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // Release signing reads <repo>/keystore.properties (git-ignored): storeFile, storePassword,
    // keyAlias, keyPassword. Without the file (or with missing keys) the release build stays
    // unsigned. No keystore lives in the repo.
    val releaseSigning = if (
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !keystoreProps.getProperty(it).isNullOrBlank() }
    ) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            buildConfigField("boolean", "ENABLE_IMAGE_SEARCH", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "ENABLE_IMAGE_SEARCH", "true")
            releaseSigning?.let { signingConfig = it }
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // "benchmark": a release-like build (not debuggable) signed with the debug key, so `adb install -r` over the debug build keeps the
    // app data. R8 is OFF (instrumented tests call app classes by name, which minification would rename), so this measures the
    // non-debuggable runtime but not R8's optimisations. Select it for instrumented tests with -PlsTestBuildType=benchmark
    // (the default stays "debug" so :app:assembleDebugAndroidTest keeps working).
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
        }
    }
    testBuildType = (findProperty("lsTestBuildType") as String?) ?: "debug"
    // the benchmark build keeps the harness helpers that live in src/debug (StallDetector, ThermalGate, ...); the large CLIP
    // debug assets stay debug-only
    sourceSets.getByName("benchmark").kotlin.srcDir("src/debug/java")
    assetPacks += ":clip_model"
    sourceSets.getByName("main").res.srcDir(layout.buildDirectory.dir("generated/notices/res").get().asFile)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            pickFirsts += setOf(
                "lib/arm64-v8a/libtensorflowlite_jni.so"
            )
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                // eclipse-collections (via hnswlib-core, androidTest only) ships duplicate licence files
                "LICENSE-EDL-1.0.txt",
                "LICENSE-EPL-1.0.txt",
                "about.html",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                // Post-quantum parameter tables pulled in via PDFBox's BouncyCastle; never loaded
                "org/bouncycastle/pqc/crypto/**/*.properties"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test:rules:1.6.1")
    // Part AN (scaling microbenchmark): HNSW reference implementation, test APK only
    androidTestImplementation("com.github.jelmerk:hnswlib-core:1.2.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    val roomVersion = "3.0.0-alpha02"

    implementation("androidx.room3:room3-runtime:$roomVersion")
    ksp("androidx.room3:room3-compiler:$roomVersion")

    implementation("androidx.sqlite:sqlite-bundled:2.6.2")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Play asset-delivery drags in fragment 1.1.0, which makes lint flag every registerForActivityResult call.
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.google.android.play:asset-delivery-ktx:2.2.2")
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    implementation(libs.pdfbox.android)
}
