#include <jni.h>
#include <android/log.h>
#include <string>

#define TAG "LlamaCppJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if LLAMA_AVAILABLE
#include "llama.h"

static llama_model* g_model = nullptr;
#endif

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_augt_localseek_ml_llm_LlamaCppJNI_nativeInitialize(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath,
    jint contextSize
) {
#if LLAMA_AVAILABLE
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    std::string modelPathStr(path == nullptr ? "" : path);
    env->ReleaseStringUTFChars(modelPath, path);

    if (g_model != nullptr) {
        llama_free_model(g_model);
        g_model = nullptr;
    }

    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    g_model = llama_load_model_from_file(modelPathStr.c_str(), modelParams);
    if (g_model == nullptr) {
        LOGE("Failed to load model: %s", modelPathStr.c_str());
        return 0;
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = static_cast<uint32_t>(contextSize > 0 ? contextSize : 2048);
    contextParams.n_batch = 512;
    contextParams.n_threads = 4;
    auto* ctx = llama_new_context_with_model(g_model, contextParams);
    if (ctx == nullptr) {
        LOGE("Failed to create llama context");
        llama_free_model(g_model);
        g_model = nullptr;
        return 0;
    }

    LOGD("llama context initialized");
    return reinterpret_cast<jlong>(ctx);
#else
    (void) env;
    (void) modelPath;
    (void) contextSize;
    LOGW("llama.cpp not compiled in; JNI init stub active");
    return 0;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_augt_localseek_ml_llm_LlamaCppJNI_nativeGenerate(
    JNIEnv* env,
    jobject /* this */,
    jlong contextPtr,
    jstring prompt,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK
) {
#if LLAMA_AVAILABLE
    auto* ctx = reinterpret_cast<llama_context*>(contextPtr);
    if (ctx == nullptr || g_model == nullptr) {
        LOGE("nativeGenerate called before model/context ready");
        return env->NewStringUTF("");
    }

    const char* promptChars = env->GetStringUTFChars(prompt, nullptr);
    std::string promptText(promptChars == nullptr ? "" : promptChars);
    env->ReleaseStringUTFChars(prompt, promptChars);

    // TODO: replace with full sampler/token loop for production-quality decoding.
    LOGD("nativeGenerate placeholder path, prompt len=%zu", promptText.size());
    std::string output = "";
    return env->NewStringUTF(output.c_str());
#else
    (void) contextPtr;
    (void) prompt;
    (void) maxTokens;
    (void) temperature;
    (void) topP;
    (void) topK;
    return env->NewStringUTF("");
#endif
}

JNIEXPORT void JNICALL
Java_com_augt_localseek_ml_llm_LlamaCppJNI_nativeCleanup(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong contextPtr
) {
#if LLAMA_AVAILABLE
    auto* ctx = reinterpret_cast<llama_context*>(contextPtr);
    if (ctx != nullptr) {
        llama_free(ctx);
    }
    if (g_model != nullptr) {
        llama_free_model(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
    LOGD("llama_jni cleanup complete");
#else
    (void) contextPtr;
    LOGD("llama_jni cleanup stub");
#endif
}

}

