cmake_minimum_required(VERSION 3.22.1)
project(localseek_llm)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

if(EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(STATUS "llama.cpp source found at ${LLAMA_DIR}")
    add_subdirectory(${LLAMA_DIR} llama_build)
    set(LLAMA_AVAILABLE TRUE)
else()
    message(WARNING "llama.cpp source not found. Building stub JNI bridge.")
    set(LLAMA_AVAILABLE FALSE)
endif()

add_library(llama_jni SHARED llama_jni.cpp)

find_library(log-lib log)
find_library(android-lib android)

if(LLAMA_AVAILABLE)
    target_include_directories(llama_jni PRIVATE
        ${LLAMA_DIR}
        ${LLAMA_DIR}/include
    )
    target_link_libraries(llama_jni llama ${android-lib} ${log-lib})
    target_compile_definitions(llama_jni PRIVATE LLAMA_AVAILABLE=1)
else()
    target_link_libraries(llama_jni ${android-lib} ${log-lib})
    target_compile_definitions(llama_jni PRIVATE LLAMA_AVAILABLE=0)
endif()

