cmake_minimum_required(VERSION 3.22.1)
project("memelm" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED TRUE)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED TRUE)

if(NOT DEFINED LLAMA_DIR)
    message(FATAL_ERROR "LLAMA_DIR is not defined. Pass it via Gradle like: cmake -DLLAMA_DIR=/path/to/llama.cpp")
endif()

# Sanity check: make sure the llama directory actually exists
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "LLAMA_DIR (${LLAMA_DIR}) does not contain a CMakeLists.txt. Check your path.")
endif()

# ---------------------------------------------------------------------------
# ARM architecture flags — enable SDOT (dot-product) instructions
# The Helio G99 and most modern ARM chips support armv8.2-a + dotprod.
# This compiles Q4_K_M quantized matmul kernels with SDOT instructions
# for ~1.5-2x speedup on quantized layers.
# ---------------------------------------------------------------------------
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -march=armv8.2-a+dotprod")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+dotprod")
endif()

# ---------------------------------------------------------------------------
# Android ABI guard — only arm64-v8a is supported
# ---------------------------------------------------------------------------
if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}. Only arm64-v8a is supported.")
    endif()
else()
    message(WARNING "ANDROID_ABI not defined — assuming desktop/CI build.")
endif()

# ---------------------------------------------------------------------------
# ggml / llama backend flags
# ---------------------------------------------------------------------------
set(GGML_CPU                ON  CACHE BOOL "Enable CPU backend"            FORCE)
set(GGML_CPU_KLEIDIAI       ON  CACHE BOOL "KleidiAI NEON kernels"         FORCE)
set(GGML_OPENMP             ON  CACHE BOOL "OpenMP threading"              FORCE)
set(GGML_BACKEND_DL         OFF CACHE BOOL "Statically link backends"      FORCE)
set(GGML_CPU_ALL_VARIANTS   OFF CACHE BOOL "Disable multi-variant CPU builds" FORCE)

set(GGML_VULKAN              OFF CACHE BOOL "Enable Vulkan backend"         FORCE)
set(LLAMA_BUILD_SHARED      OFF CACHE BOOL "Build llama as static lib"     FORCE)
set(LLAMA_BUILD_MTMD        OFF CACHE BOOL "Do not build mtmd standalone"  FORCE)
set(LLAMA_BUILD_COMMON      OFF CACHE BOOL "Skip common library"           FORCE)

# Tell llama's build system NOT to build mtmd as a separate target.
# We compile the mtmd sources directly into our shared library below,
# which avoids duplicate symbols and the "-lmtmd not found" linker error.
set(LLAMA_BUILD_MTMD   OFF CACHE BOOL "Do not build mtmd as a standalone target" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "Skip common library" FORCE)

# ---------------------------------------------------------------------------
# Vulkan glslc path (required when building Vulkan shaders via NDK)
# ---------------------------------------------------------------------------
if(GGML_VULKAN AND ANDROID AND DEFINED ANDROID_NDK AND DEFINED ANDROID_HOST_TAG)
    set(Vulkan_GLSLC_EXECUTABLE
            "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc"
            CACHE FILEPATH "NDK glslc path" FORCE)
    message(STATUS "NDK glslc: ${Vulkan_GLSLC_EXECUTABLE}")
endif()

# ---------------------------------------------------------------------------
# Build llama.cpp (produces target: llama)
# ---------------------------------------------------------------------------
add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama_build)

# ---------------------------------------------------------------------------
# Collect mtmd sources (compiled once, into our library)
# Exclude the CLI entry point and debug utilities — not needed on Android.
# ---------------------------------------------------------------------------
file(GLOB_RECURSE MTMD_SOURCES
        "${LLAMA_DIR}/tools/mtmd/*.cpp"
        "${LLAMA_DIR}/tools/mtmd/models/*.cpp"
)
list(FILTER MTMD_SOURCES EXCLUDE REGEX ".*/mtmd-cli\\.cpp$")
list(FILTER MTMD_SOURCES EXCLUDE REGEX ".*/mtmd/debug/.*\\.cpp$")

if(NOT MTMD_SOURCES)
    message(FATAL_ERROR
            "No mtmd sources found under ${LLAMA_DIR}/tools/mtmd/. "
            "Check that your llama.cpp submodule is initialised and up to date.")
endif()
message(STATUS "mtmd sources: ${MTMD_SOURCES}")

# ---------------------------------------------------------------------------
# Our JNI shared library
# ---------------------------------------------------------------------------
add_library(${CMAKE_PROJECT_NAME} SHARED
        memelm.cpp
        LLMInference.cpp
        EmbeddingEngine.cpp
        ${MTMD_SOURCES}
)

# ---------------------------------------------------------------------------
# Include directories
# NOTE: all paths are derived from LLAMA_DIR — no separate LLAMA_SRC needed.
# ---------------------------------------------------------------------------
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_DIR}                        # llama.h, llama-cpp.h
        ${LLAMA_DIR}/include                # public llama headers
        ${LLAMA_DIR}/ggml/include           # ggml.h
        ${LLAMA_DIR}/ggml/src               # ggml internals (needed by mtmd)
        ${LLAMA_DIR}/tools/mtmd             # mtmd.h, mtmd-helper.h
        ${LLAMA_DIR}/tools/mtmd/models      # model-specific vision headers
        ${LLAMA_DIR}/tools/mtmd/image       # image helpers
        ${LLAMA_DIR}/tools/mtmd/audio       # audio helpers (Qwen2-Audio etc.)
        ${LLAMA_DIR}/vendor                 # third-party headers bundled with llama
)

# ---------------------------------------------------------------------------
# System libraries
# ---------------------------------------------------------------------------
find_library(vulkan-lib    vulkan    REQUIRED)
find_library(jnigraphics-lib jnigraphics REQUIRED)
find_library(log-lib       log       REQUIRED)
find_library(android-lib   android   REQUIRED)

# ---------------------------------------------------------------------------
# Link order matters: mtmd symbols must resolve against llama, which resolves
# against ggml. Put most-dependent first, least-dependent last.
# ---------------------------------------------------------------------------
target_link_libraries(${CMAKE_PROJECT_NAME}
        -Wl,--whole-archive llama -Wl,--no-whole-archive
        ${vulkan-lib}
        ${jnigraphics-lib}
        ${android-lib}
        ${log-lib}
)