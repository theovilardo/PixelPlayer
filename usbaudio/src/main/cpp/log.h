#pragma once

#include <android/log.h>

#define UA_LOG_TAG "UsbAudioNative"
#define UA_LOGI(...) __android_log_print(ANDROID_LOG_INFO, UA_LOG_TAG, __VA_ARGS__)
#define UA_LOGW(...) __android_log_print(ANDROID_LOG_WARN, UA_LOG_TAG, __VA_ARGS__)
#define UA_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, UA_LOG_TAG, __VA_ARGS__)
