#include <jni.h>

#include <cstdio>

#include "libusb.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeGetVersion(JNIEnv* env, jobject /*thiz*/) {
    const libusb_version* v = libusb_get_version();
    char buf[64];
    std::snprintf(buf, sizeof(buf), "libusb %u.%u.%u", v->major, v->minor, v->micro);
    return env->NewStringUTF(buf);
}
