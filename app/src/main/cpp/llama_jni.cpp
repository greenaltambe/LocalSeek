#include <jni.h>
#include <android/log.h>

#define TAG "LlamaCppJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_augt_localseek_ml_llm_LlamaCppJNI_nativeInitialize(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath,
    jint contextSize
) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGD("llama_jni placeholder init. model=%s, n_ctx=%d", path, contextSize);
    env->ReleaseStringUTFChars(modelPath, path);
    // TODO: wire llama.cpp context and return non-zero pointer.
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_augt_localseek_ml_llm_LlamaCppJNI_nativeGenerate(
    JNIEnv* env,
    jobject /* this */,
    jlong /* contextPtr */,
    jstring /* prompt */,
    jint /* maxTokens */,
    jfloat /* temperature */,
    jfloat /* topP */,
    jint /* topK */
) {
    const char* msg = "llama_jni placeholder: native generation not wired yet";
    return env->NewStringUTF(msg);
}

JNIEXPORT void JNICALL
Java_com_augt_localseek_ml_llm_LlamaCppJNI_nativeCleanup(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong /* contextPtr */
) {
    LOGD("llama_jni placeholder cleanup");
}

}

