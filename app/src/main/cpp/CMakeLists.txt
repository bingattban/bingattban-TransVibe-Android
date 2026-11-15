cmake_minimum_required(VERSION 3.22.1)

# Keep the existing Android JNI runtime; do not change the STL linkage.
set(ANDROID_STL c++_static CACHE STRING "Android C++ runtime" FORCE)
set(CMAKE_ANDROID_STL_TYPE c++_static CACHE STRING "Android C++ runtime" FORCE)
project(transvibe_native LANGUAGES C CXX)

# Code On the Go: avoid CMake/Ninja regeneration loops on shared storage.
set(CMAKE_SUPPRESS_REGENERATION ON CACHE BOOL "" FORCE)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# Broad arm64-v8a compatibility, with an explicit opt-in for verified CPUs.
option(TRANSVIBE_ARMV82_DOTPROD_FP16 "Require ARMv8.2 dotprod and FP16" OFF)
if(TRANSVIBE_ARMV82_DOTPROD_FP16)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
else()
    set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
endif()

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
foreach(_backend CUDA METAL SYCL OPENVINO CANN MUSA WEBGPU VIRTGPU BLAS HIP RPC ZDNN ZENDNN)
    set(GGML_${_backend} OFF CACHE BOOL "" FORCE)
endforeach()

set(WHISPERCPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp")
if(NOT EXISTS "${WHISPERCPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Vendored whisper.cpp source is missing: ${WHISPERCPP_DIR}")
endif()

# Keep the verified vendored whisper.cpp 1.9.4 version.
file(READ "${WHISPERCPP_DIR}/CMakeLists.txt" WHISPER_TOPLEVEL_CMAKE)
string(FIND "${WHISPER_TOPLEVEL_CMAKE}" "set(WHISPER_VERSION_MAJOR 1)" WHISPER_MAJOR_POS)
string(FIND "${WHISPER_TOPLEVEL_CMAKE}" "set(WHISPER_VERSION_MINOR 9)" WHISPER_MINOR_POS)
string(FIND "${WHISPER_TOPLEVEL_CMAKE}" "set(WHISPER_VERSION_PATCH 4)" WHISPER_PATCH_POS)
if(WHISPER_MAJOR_POS EQUAL -1 OR WHISPER_MINOR_POS EQUAL -1 OR WHISPER_PATCH_POS EQUAL -1)
    message(FATAL_ERROR "Unexpected whisper.cpp version. Read WHISPER_VERSION.md before updating.")
endif()
add_subdirectory("${WHISPERCPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/whispercpp")

# Keep optimized Whisper inference in Debug, but omit the large debug symbols
# from its static libraries to reduce linker memory pressure on Android phones.
foreach(_target whisper ggml-base ggml-cpu)
    if(TARGET ${_target})
        target_compile_options(${_target} PRIVATE
            "$<$<CONFIG:Debug>:-O2>"
            "$<$<CONFIG:Debug>:-g0>"
            "$<$<NOT:$<CONFIG:Debug>>:-O3>")
    endif()
endforeach()

add_library(transvibe_native SHARED native-lib.cpp)
target_include_directories(transvibe_native PRIVATE "${WHISPERCPP_DIR}/include")
target_link_libraries(transvibe_native PRIVATE whisper log android)
target_compile_options(transvibe_native PRIVATE
    "$<$<CONFIG:Debug>:-O2>"
    "$<$<NOT:$<CONFIG:Debug>>:-O3>"
    "$<$<NOT:$<CONFIG:Debug>>:-fvisibility=hidden>"
    "$<$<NOT:$<CONFIG:Debug>>:-fvisibility-inlines-hidden>"
    "$<$<NOT:$<CONFIG:Debug>>:-ffunction-sections>"
    "$<$<NOT:$<CONFIG:Debug>>:-fdata-sections>")

# LLVM LLD can abort while linking large native libraries on an ARM64 phone.
# Single-threaded linking is a targeted mitigation, not a guaranteed LLVM fix.
if(ANDROID)
    target_link_options(transvibe_native PRIVATE "-Wl,--threads=1")
endif()
target_link_options(transvibe_native PRIVATE
    "$<$<NOT:$<CONFIG:Debug>>:-Wl,--gc-sections>")
set_target_properties(transvibe_native PROPERTIES OUTPUT_NAME "transvibe_native")
