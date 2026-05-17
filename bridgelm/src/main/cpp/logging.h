//
//  From: https://github.com/ggml-org/llama.cpp/blob/f3c3e0e9a087835639733485b8900b195ba4ca47/examples/llama.android/lib/src/main/cpp/logging.h
//

#ifndef AICHAT_LOGGING_H
#define AICHAT_LOGGING_H

#endif

#pragma once
#include <android/log.h>

#define LOG_TAG "bridgelm"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/*
# Additional Information model token ID
print_info: BOS token             = 248045 '<|im_start|>'
print_info: EOS token             = 248046 '<|im_end|>'
print_info: EOT token             = 248046 '<|im_end|>'
print_info: UNK token             = 248077 '<unk>'
print_info: PAD token             = 248044 '<|endoftext|>'
print_info: LF token              = 198 'Ċ'
print_info: FIM PRE token         = 248060 '<|fim_prefix|>'
print_info: FIM SUF token         = 248062 '<|fim_suffix|>'
print_info: FIM MID token         = 248061 '<|fim_middle|>'
print_info: FIM PAD token         = 248063 '<|fim_pad|>'
print_info: FIM REP token         = 248064 '<|repo_name|>'
print_info: FIM SEP token         = 248065 '<|file_sep|>'
print_info: EOG token             = 248044 '<|endoftext|>'
print_info: EOG token             = 248046 '<|im_end|>'
print_info: EOG token             = 248063 '<|fim_pad|>'
print_info: EOG token             = 248064 '<|repo_name|>'
print_info: EOG token             = 248065 '<|file_sep|>'

*/
