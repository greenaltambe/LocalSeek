cmake_minimum_required(VERSION 3.22.1)
project(llama_android)

set(CMAKE_CXX_STANDARD 17)

# Optional JNI bridge build file.
# This is not active until externalNativeBuild is enabled in app/build.gradle.kts.
add_library(llama_jni SHARED llama_jni.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    llama_jni
    ${log-lib}
    ${android-lib}
)

