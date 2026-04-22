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

# ============================================================
# TFLite / NNAPI
# ============================================================
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ============================================================
# OkHttp
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**

# ============================================================
# Room3
# ============================================================
-keep class * extends androidx.room3.RoomDatabase
-keep @androidx.room3.Entity class *
-keepclassmembers class * {
	@androidx.room3.* <fields>;
	@androidx.room3.* <methods>;
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
