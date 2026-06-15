cmake_minimum_required(VERSION 3.22.1)

project(counseling_gemma)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp build-llama)

add_library(
        gemma_jni
        SHARED
        gemma_jni.cpp
)

find_library(log-lib log)

target_include_directories(
        gemma_jni
        PRIVATE
        llama.cpp
        llama.cpp/common
        llama.cpp/include
        llama.cpp/ggml/include
        llama.cpp/ggml/src
)

target_link_libraries(
        gemma_jni
        PRIVATE
        llama
        llama-common
        ${log-lib}
)
