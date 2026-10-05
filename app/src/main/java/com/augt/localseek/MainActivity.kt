package com.augt.localseek

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.indexing.IndexScheduler
import com.augt.localseek.tools.ThemeSettings
import com.augt.localseek.tools.ToolsRepository
import com.augt.localseek.ui.LaunchRequest
import com.augt.localseek.ui.SearchViewModel
import com.augt.localseek.ui.onboarding.OnboardingPermissions
import com.augt.localseek.ui.onboarding.PermissionStep
import kotlinx.coroutines.flow.first
import com.augt.localseek.ui.theme.LocalSeekTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: SearchViewModel by viewModels()

    /**
     * True while the onboarding tour drives permission requests. The existing launcher callbacks normally chain
     * to the next permission prompt; during onboarding each permission is asked on its own page, so they only
     * start indexing instead.
     */
    @Volatile
    private var onboardingActive = false

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                continueOrIndex { checkContactsPermissionAndIndex() }
            }
        }
    }

    // Launcher for older Android <= 10 storage permission dialog
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) continueOrIndex { checkContactsPermissionAndIndex() }
    }

    private val requestContactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        continueOrIndex { checkImagesPermissionAndIndex() }
    }

    private val requestImagesPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // We continue to indexing regardless of whether media images permission is granted.
        // The ImageIndexer will handle missing permission gracefully.
        startIndexing()
    }

    // Notification permission (Android 13+): asked once, in context (when indexing starts after onboarding), never cold.
    private val requestNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, indexing and the in-app banner work either way */ }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(Manifest.permission.POST_NOTIFICATIONS)) return
        lifecycleScope.launch {
            val tools = ToolsRepository(applicationContext)
            if (tools.uiPrefs.first().notificationPermissionAsked) return@launch
            tools.updateUiPrefs { copy(notificationPermissionAsked = true) }
            requestNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Initialize PDFBox for parsing .pdf files
        PDFBoxResourceLoader.init(applicationContext)

        // Launcher shortcut or shared text (only on a fresh start: a rotation must not replay it)
        if (savedInstanceState == null) handleLaunchIntent(intent)

        // Check permissions and start indexing real files only on initial cold creation
        if (savedInstanceState == null) {
            // On the very first run the onboarding tour asks for each permission on its own page instead.
            lifecycleScope.launch {
                val tools = ToolsRepository(applicationContext)
                // One-time: add the a/c/f/i/s scoped prefixes to an existing prefix list without overwriting anything.
                tools.migrateScopedPrefixes()
                tools.migrateColonPrefixes()
                if (tools.onboardingCompleted.first()) checkPermissionsAndIndex()
            }
            // Set up the periodic 6-hour background indexer
            IndexScheduler.schedulePeriodicIndex(this)
        }
        
        setContent {
            val themeSettings by remember { ToolsRepository(applicationContext).theme }
                .collectAsState(initial = ThemeSettings())
            LocalSeekTheme(themeSettings = themeSettings) {
                SearchApp(viewModel = viewModel, onboardingPermissions = onboardingPermissions)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        intent ?: return
        val request = LaunchRequest.fromIntent(
            action = intent.action,
            mimeType = intent.type,
            sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            scopeExtra = intent.getStringExtra(LaunchRequest.EXTRA_SCOPE)
        )
        viewModel.applyLaunchRequest(request)
    }

    /** Hands the onboarding screen the same request paths the app has always used. */
    private val onboardingPermissions = object : OnboardingPermissions {
        override fun isGranted(step: PermissionStep): Boolean = when (step) {
            PermissionStep.CONTACTS -> has(Manifest.permission.READ_CONTACTS)
            PermissionStep.FILES ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
                else has(Manifest.permission.READ_EXTERNAL_STORAGE)
            PermissionStep.PHOTOS -> has(imagesPermission())
        }

        override fun request(step: PermissionStep) {
            if (isGranted(step)) return
            onboardingActive = true
            when (step) {
                PermissionStep.CONTACTS -> requestContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                PermissionStep.FILES -> checkPermissionsAndIndex()
                PermissionStep.PHOTOS -> requestImagesPermissionLauncher.launch(imagesPermission())
            }
        }

        override fun onOnboardingFinished() {
            onboardingActive = false
            startIndexing()
        }
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun imagesPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun continueOrIndex(next: () -> Unit) {
        if (onboardingActive) startIndexing() else next()
    }

    private fun checkPermissionsAndIndex() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+)
            if (Environment.isExternalStorageManager()) {
                checkContactsPermissionAndIndex()
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                manageStorageLauncher.launch(intent)
            }
        } else {
            // Android 10 and below
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                checkContactsPermissionAndIndex()
            } else {
                requestPermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun checkContactsPermissionAndIndex() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            checkImagesPermissionAndIndex()
        } else {
            requestContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun checkImagesPermissionAndIndex() {
        if (!BuildConfig.ENABLE_IMAGE_SEARCH) {
            startIndexing()
            return
        }
        val permission = imagesPermission()
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            startIndexing()
        } else {
            requestImagesPermissionLauncher.launch(permission)
        }
    }

    private fun startIndexing() {
        // Trigger the WorkManager to scan the device!
        IndexScheduler.scheduleImmediateIndex(this)
        if (!onboardingActive) maybeRequestNotificationPermission()
    }
}
