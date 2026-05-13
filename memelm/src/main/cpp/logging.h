//
//  From: https://github.com/ggml-org/llama.cpp/blob/f3c3e0e9a087835639733485b8900b195ba4ca47/examples/llama.android/lib/src/main/cpp/logging.h
//

#ifndef AICHAT_LOGGING_H
#define AICHAT_LOGGING_H

#endif

#pragma once
#include <android/log.h>

#define LOG_TAG "memelm"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)