cmake_minimum_required(VERSION 3.31.6)
project("memelm" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
        set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan backend" FORCE)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

if(NOT DEFINED LLAMA_DIR)
    message(FATAL_ERROR "LLAMA_DIR is not defined. Pass it via Gradle arguments.")
endif()

set(GGML_CPU ON CACHE BOOL "Enable CPU backend" FORCE)
if(GGML_VULKAN AND ANDROID)
    set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc" CACHE FILEPATH "NDK glslc")
    message(STATUS "Using NDK glslc: ${Vulkan_GLSLC_EXECUTABLE}")
endif()

set(LLAMA_BUILD_MTMD ON CACHE BOOL "Build multimodal support" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "Skip common library" FORCE)
add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama_build)

file(GLOB_RECURSE MTMD_SOURCES
        ${LLAMA_DIR}/tools/mtmd/*.cpp
        ${LLAMA_DIR}/tools/mtmd/models/*.cpp
)
list(FILTER MTMD_SOURCES EXCLUDE REGEX ".*/mtmd-cli\\.cpp$")
list(FILTER MTMD_SOURCES EXCLUDE REGEX ".*/mtmd/debug/.*\\.cpp$")

# JNI Libraries
add_library(${CMAKE_PROJECT_NAME} SHARED
        memelm.cpp
        LLMInference.cpp
        ${MTMD_SOURCES}
)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_DIR}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_DIR}/ggml/include
        ${LLAMA_SRC}/ggml/src
        ${LLAMA_DIR}/tools/mtmd
        ${LLAMA_DIR}/tools/mtmd/models
        ${LLAMA_DIR}/tools/mtmd/image
        ${LLAMA_DIR}/tools/mtmd/audio
        ${LLAMA_DIR}/vendor
)

find_library(vulkan-lib vulkan)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        android
        ${vulkan-lib}
        ${jnigraphics-lib}
        log
)