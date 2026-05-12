cmake_minimum_required(VERSION 3.31.6)
project("memelm")

# llama.cpp as a sub‑project (with Vulkan ON)
if(NOT DEFINED LLAMA_DIR)
    message(FATAL_ERROR "LLAMA_DIR is not defined. Pass it via Gradle arguments.")
endif()

set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan backend" FORCE)
if(GGML_VULKAN AND ANDROID)
    # Android NDK contains glslc under shader-tools/<host_tag>/
    set(NDK_SHADER_TOOLS "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}")
    find_program(Vulkan_GLSLC_EXECUTABLE
            NAMES glslc
            PATHS "${NDK_SHADER_TOOLS}"
            NO_DEFAULT_PATH
            REQUIRED
    )
    message(STATUS "Using NDK glslc: ${Vulkan_GLSLC_EXECUTABLE}")
endif()

set(BUILD_TOOLS ON CACHE BOOL "Build mtmd tools library" FORCE)
add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama_build)

# JNI Libraries
add_library(memelm SHARED
        memelm.cpp
        GGUFReader.cpp
        LLMInference.cpp
)

target_include_directories(memelm PRIVATE
        ${LLAMA_DIR}
        ${LLAMA_DIR}/examples/llava
)

target_link_libraries(memelm mtmd llama log)