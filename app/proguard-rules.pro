# ============================================================
# PDF Box - optional JP2 decoder (not present on Android)
# ============================================================
-dontwarn com.gemalto.jp2.**

# ============================================================
# PDFBox Android
# ============================================================
-dontwarn org.apache.pdfbox.**
-dontwarn org.apache.fontbox.**
-dontwarn org.apache.commons.**
-dontwarn com.tom_roush.pdfbox.**

# ============================================================
# TFLite / NNAPI
# ============================================================
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ============================================================
# Room & WorkDatabase (Room 2 and Room 3)
# ============================================================
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keep class * extends androidx.room3.RoomDatabase {
    public <init>();
}
-keep class *_Impl {
    public <init>(...);
}
-keep @androidx.room3.Entity class *
-keepclassmembers class * {
	@androidx.room3.* <fields>;
	@androidx.room3.* <methods>;
}

# ============================================================
# WorkManager
# ============================================================
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger {
    public <init>();
}

# ============================================================
# Coroutines / DataStore
# ============================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.datastore.**

# ============================================================
# Generic metadata retention
# ============================================================
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# ============================================================
# Privacy: strip verbose/debug/info logging from release builds.
# Those logs can contain query text or file names and end up in bug reports.
# Log.w / Log.e are kept (they must not contain private text; see LogPrivacyTest).
# ============================================================
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
