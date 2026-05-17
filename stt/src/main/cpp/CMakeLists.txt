cmake_minimum_required(VERSION 3.22.1)
project("stt" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED TRUE)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED TRUE)

if(NOT DEFINED WHISPER_LIB_DIR)
    message(FATAL_ERROR "WHISPER_LIB_DIR is not defined.")
endif()

if(NOT EXISTS "${WHISPER_LIB_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "WHISPER_LIB_DIR (${WHISPER_LIB_DIR}) does not contain a CMakeLists.txt. Check your path.")
endif()

# TODO: Adjust this based on the whisper.cpp sample