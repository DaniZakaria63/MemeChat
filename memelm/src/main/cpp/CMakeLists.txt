cmake_minimum_required(VERSION 3.18)
project("memelm")

# llama.cpp as a sub‑project (with Vulkan ON)
if(NOT DEFINED LLAMA_DIR)
    message(FATAL_ERROR "LLAMA_DIR is not defined. Pass it via Gradle arguments.")
endif()

set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan backend" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF)

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama_build)

# Build llava static library from llama.cpp's examples/llava
file(GLOB LLAVA_SOURCES
        ${LLAMA_DIR}/examples/llava/*.cpp
        ${LLAMA_DIR}/examples/llava/*.h
)
add_library(llava STATIC ${LLAVA_SOURCES})
target_include_directories(llava PRIVATE
        ${LLAMA_DIR}
        ${LLAMA_DIR}/examples/llava
)
target_link_libraries(llava llama)

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

target_link_libraries(memelm
        llava
        llama
        log
)