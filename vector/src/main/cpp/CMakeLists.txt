cmake_minimum_required(VERSION 3.22.1)
project("vector" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED TRUE)

if(NOT DEFINED FAISS_DIR)
    message(FATAL_ERROR
        "FAISS_DIR not defined. Add -DFAISS_DIR=<path> in build.gradle.kts")
endif()
if(NOT EXISTS "${FAISS_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "FAISS_DIR (${FAISS_DIR}) does not contain CMakeLists.txt")
endif()

set(FAISS_ENABLE_GPU     OFF CACHE BOOL "" FORCE)
set(FAISS_ENABLE_PYTHON  OFF CACHE BOOL "" FORCE)
set(FAISS_ENABLE_C_API   OFF CACHE BOOL "" FORCE)
set(FAISS_ENABLE_EXTRAS  OFF CACHE BOOL "" FORCE)
set(FAISS_ENABLE_MKL     OFF CACHE BOOL "" FORCE)
set(FAISS_ENABLE_SVS     OFF CACHE BOOL "" FORCE)
set(FAISS_ENABLE_METAL   OFF CACHE BOOL "" FORCE)
set(FAISS_ENABLE_CUVS    OFF CACHE BOOL "" FORCE)
set(FAISS_OPT_LEVEL      "generic" CACHE STRING "" FORCE)

# Import prebuilt OpenBLAS for ARM64
add_library(openblas SHARED IMPORTED)
set_target_properties(openblas PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libopenblas.so
)
set(BLAS_LIBRARIES   openblas CACHE STRING "" FORCE)
set(LAPACK_LIBRARIES openblas CACHE STRING "" FORCE)

add_subdirectory(${FAISS_DIR} ${CMAKE_BINARY_DIR}/faiss)

add_library(${CMAKE_PROJECT_NAME} SHARED vector.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
    faiss
    android
    log
)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -O3)
