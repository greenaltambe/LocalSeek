# Building llama.cpp for Android (Optional)
This project includes a placeholder JNI bridge in:
- `app/src/main/cpp/llama_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
Native build is **not enabled by default** in Gradle, so app builds remain stable without NDK.
## Prerequisites
- Android NDK 26+
- CMake 3.22+
- Git
## Steps
1. Clone llama.cpp under `app/src/main/cpp/`:
```bash
git clone https://github.com/ggerganov/llama.cpp app/src/main/cpp/llama.cpp
```
2. Extend `app/src/main/cpp/CMakeLists.txt` to include llama.cpp sources or `add_subdirectory(llama.cpp)`.
3. Enable `externalNativeBuild` in `app/build.gradle.kts`.
4. Sync and build from Android Studio.
## Verify
Look for generated `libllama_jni.so` under build intermediates.
## Model Placement
Put Phi-3 model at:
- `app/src/main/assets/models/phi3.gguf`
Runtime behavior in this branch:
- If native `llama_jni` is present -> uses JNI path.
- If native lib is absent -> falls back to extractive answerer.
