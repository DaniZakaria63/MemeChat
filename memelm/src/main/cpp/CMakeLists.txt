cmake_minimum_required(VERSION 3.31.6)
project("memelm")

# llama.cpp as a sub‑project (with Vulkan ON)
if(NOT DEFINED LLAMA_DIR)
    message(FATAL_ERROR "LLAMA_DIR is not defined. Pass it via Gradle arguments.")
endif()

set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan backend" FORCE)
set(GGML_CPU ON CACHE BOOL "Enable CPU backend" FORCE)
if(GGML_VULKAN AND ANDROID)
    set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc" CACHE FILEPATH "NDK glslc")
    message(STATUS "Using NDK glslc: ${Vulkan_GLSLC_EXECUTABLE}")
endif()

set(LLAMA_BUILD_TOOLS ON CACHE BOOL "Build mtmd library" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "Skip common library" FORCE)
add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama_build)

file(GLOB_RECURSE MTMD_SOURCES
        ${LLAMA_DIR}/tools/mtmd/*.cpp
        ${LLAMA_DIR}/tools/mtmd/models/*.cpp
)
list(FILTER MTMD_SOURCES EXCLUDE REGEX ".*/mtmd-cli\\.cpp$")
list(FILTER MTMD_SOURCES EXCLUDE REGEX ".*/mtmd/debug/.*\\.cpp$")

# JNI Libraries
add_library(memelm SHARED
        memelm.cpp
        LLMInference.cpp
        ${MTMD_SOURCES}
)

target_include_directories(memelm PRIVATE
        ${LLAMA_DIR}
        ${LLAMA_DIR}/ggml/include
        ${LLAMA_DIR}/tools/mtmd
        ${LLAMA_DIR}/tools/mtmd/models
        ${LLAMA_DIR}/tools/mtmd/image
        ${LLAMA_DIR}/tools/mtmd/audio
        ${LLAMA_DIR}/vendor
)

find_library(vulkan-lib vulkan)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(memelm
        llama
        ${vulkan-lib}
        ${jnigraphics-lib}
        log
)