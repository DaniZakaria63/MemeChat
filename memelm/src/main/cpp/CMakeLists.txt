cmake_minimum_required(VERSION 3.22.1)
project("memelm")

# Point to your llama.cpp submodule
add_subdirectory(../../../../llama.cpp llama.cpp)
set(LLAMA_DIR_RELATIVE "../../../../llama.cpp")
get_filename_component(LLAMA_DIR ${LLAMA_DIR_RELATIVE} ABSOLUTE)
set(GGML_DIR ${LLAMA_DIR}/ggml)
set(COMMON_DIR ${LLAMA_DIR}/common)
set(VENDOR_DIR ${LLAMA_DIR}/vendor)

# Point to mtmd (multimodal) sources in llama.cpp
add_subdirectory(${LLAMA_DIR}/tools/mtmd mtmd)

# Compiler flags for size optimization and symbol hiding
target_compile_options(llama PUBLIC -fvisibility=hidden -fvisibility-inlines-hidden)
target_compile_options(llama PUBLIC -ffunction-sections -fdata-sections)
target_link_options(llama PRIVATE -Wl,--gc-sections -flto -Wl,--exclude-libs,ALL)

# Build the shared library
function(build_library target_name)
    add_library(${target_name} SHARED LLMInference.cpp memelm.cpp)
    target_include_directories(${target_name} PUBLIC
            ${COMMON_DIR}
            ${GGML_DIR}/include ${GGML_DIR}/src ${GGML_DIR}/src/ggml-cpu
            ${LLAMA_DIR}/include
            ${VENDOR_DIR}
    )
    target_compile_features(${target_name} PUBLIC cxx_std_17)
    # ... compiler flags ...
    target_link_libraries(${target_name} android log llama mtmd vulkan)
endfunction()

build_library("memelm")  # Creates libmemelm.so
