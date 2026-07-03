#include <jni.h>

#include <cstdio>
#include <memory>
#include <mutex>
#include <vector>

#include "iso_stream.h"
#include "libusb.h"
#include "log.h"
#include "uac_device.h"

namespace {

/** Everything one Kotlin UsbAudioSession owns natively. */
struct NativeSession {
    std::unique_ptr<UacDevice> device;
    std::shared_ptr<IsoStream> stream;
    std::mutex mutex;
};

NativeSession* fromHandle(jlong handle) {
    return reinterpret_cast<NativeSession*>(handle);
}

std::shared_ptr<IsoStream> streamOf(jlong handle) {
    NativeSession* session = fromHandle(handle);
    if (session == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(session->mutex);
    return session->stream;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeGetVersion(JNIEnv* env, jobject) {
    const libusb_version* v = libusb_get_version();
    char buf[64];
    std::snprintf(buf, sizeof(buf), "libusb %u.%u.%u", v->major, v->minor, v->micro);
    return env->NewStringUTF(buf);
}

JNIEXPORT jlong JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeCreate(JNIEnv*, jobject, jint fd) {
    std::string error;
    auto device = UacDevice::create(fd, &error);
    if (!device) {
        UA_LOGE("nativeCreate failed: %s", error.c_str());
        return 0;
    }
    auto* session = new NativeSession();
    session->device = std::move(device);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    NativeSession* session = fromHandle(handle);
    if (session == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (session->stream) session->stream->stop();
        session->stream.reset();
        session->device.reset();
    }
    delete session;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeClaim(
    JNIEnv*, jobject, jlong handle, jint acInterface, jint asInterface) {
    NativeSession* session = fromHandle(handle);
    if (session == nullptr) return -1;
    std::lock_guard<std::mutex> lock(session->mutex);
    return session->device->claimInterfaces(acInterface, asInterface) ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeConfigureStream(
    JNIEnv*, jobject, jlong handle, jint asInterface, jint altSetting, jint endpointAddress,
    jint feedbackEndpointAddress, jint rateHz, jint channels, jint subslotBytes,
    jint intervalCode, jint maxPacketSize, jint ringBufferMs) {
    NativeSession* session = fromHandle(handle);
    if (session == nullptr) return -1;
    std::lock_guard<std::mutex> lock(session->mutex);

    if (session->stream) {
        session->stream->stop();
        session->stream.reset();
    }
    if (!session->device->setAltSetting(asInterface, altSetting)) {
        return -1;
    }

    IsoStream::Config config;
    config.endpointAddress = static_cast<uint8_t>(endpointAddress);
    config.feedbackEndpointAddress = static_cast<uint8_t>(feedbackEndpointAddress);
    config.rateHz = static_cast<uint32_t>(rateHz);
    config.channels = channels;
    config.subslotBytes = subslotBytes;
    config.intervalCode = intervalCode;
    config.maxPacketSize = maxPacketSize;
    config.highSpeed = session->device->isHighSpeed();
    config.ringBufferMs = ringBufferMs;

    session->stream = std::make_shared<IsoStream>(*session->device, config);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeSetSampleRate(
    JNIEnv*, jobject, jlong handle, jint uacVersion, jint clockId, jint acInterface,
    jint endpointAddress, jint rateHz) {
    NativeSession* session = fromHandle(handle);
    if (session == nullptr) return -1;
    std::lock_guard<std::mutex> lock(session->mutex);
    const bool ok = uacVersion == 2
        ? session->device->setSampleRateUac2(clockId, acInterface, static_cast<uint32_t>(rateHz))
        : session->device->setSampleRateUac1(endpointAddress, static_cast<uint32_t>(rateHz));
    return ok ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeSetClockSelector(
    JNIEnv*, jobject, jlong handle, jint selectorId, jint acInterface, jint pin) {
    NativeSession* session = fromHandle(handle);
    if (session == nullptr) return -1;
    std::lock_guard<std::mutex> lock(session->mutex);
    return session->device->setClockSelector(selectorId, acInterface, pin) ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeControlTransferIn(
    JNIEnv* env, jobject, jlong handle, jint requestType, jint request, jint value, jint index,
    jbyteArray buffer) {
    NativeSession* session = fromHandle(handle);
    if (session == nullptr || buffer == nullptr) return -1;
    const jsize length = env->GetArrayLength(buffer);
    if (length <= 0 || length > 0xFFFF) return -1;
    std::vector<uint8_t> scratch(static_cast<size_t>(length));
    int read;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        read = session->device->controlTransferIn(
            static_cast<uint8_t>(requestType), static_cast<uint8_t>(request),
            static_cast<uint16_t>(value), static_cast<uint16_t>(index),
            scratch.data(), static_cast<uint16_t>(length));
    }
    if (read > 0) {
        env->SetByteArrayRegion(buffer, 0, read, reinterpret_cast<jbyte*>(scratch.data()));
    }
    return read;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeStart(JNIEnv*, jobject, jlong handle) {
    auto stream = streamOf(handle);
    if (!stream) return -1;
    return stream->start() ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativePause(JNIEnv*, jobject, jlong handle) {
    auto stream = streamOf(handle);
    if (!stream) return -1;
    stream->pause();
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeResume(JNIEnv*, jobject, jlong handle) {
    auto stream = streamOf(handle);
    if (!stream) return -1;
    stream->resume();
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeFlush(JNIEnv*, jobject, jlong handle) {
    auto stream = streamOf(handle);
    if (!stream) return -1;
    stream->flush();
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeStop(
    JNIEnv*, jobject, jlong handle, jint asInterface) {
    NativeSession* session = fromHandle(handle);
    if (session == nullptr) return -1;
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->stream) {
        session->stream->stop();
        session->stream.reset();
    }
    // Alt setting 0 releases the reserved isochronous bandwidth.
    session->device->setAltSetting(asInterface, 0);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeWrite(
    JNIEnv* env, jobject, jlong handle, jobject buffer, jint offset, jint size) {
    auto stream = streamOf(handle);
    if (!stream) return -1;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr) return -2; // not a direct buffer
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (offset < 0 || size < 0 || offset + size > capacity) return -3;
    return stream->write(base + offset, static_cast<size_t>(size));
}

JNIEXPORT jlong JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeGetPlayedFrames(
    JNIEnv*, jobject, jlong handle) {
    auto stream = streamOf(handle);
    return stream ? static_cast<jlong>(stream->playedFrames()) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeGetConsumedFrames(
    JNIEnv*, jobject, jlong handle) {
    auto stream = streamOf(handle);
    return stream ? static_cast<jlong>(stream->consumedFrames()) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeGetBufferedFrames(
    JNIEnv*, jobject, jlong handle) {
    auto stream = streamOf(handle);
    return stream ? static_cast<jlong>(stream->bufferedFrames()) : 0;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeGetXrunCount(
    JNIEnv*, jobject, jlong handle) {
    auto stream = streamOf(handle);
    return stream ? stream->xrunCount() : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeIsAlive(
    JNIEnv*, jobject, jlong handle) {
    auto stream = streamOf(handle);
    return stream && stream->alive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeGetVolumeRangeDb256(
    JNIEnv* env, jobject, jlong handle, jint uacVersion, jint unitId, jint acInterface) {
    NativeSession* session = fromHandle(handle);
    if (session == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(session->mutex);
    int32_t range[3] = {0, 0, 0};
    if (!session->device->getVolumeRangeDb256(uacVersion, unitId, acInterface, range)) {
        return nullptr;
    }
    jintArray out = env->NewIntArray(3);
    if (out == nullptr) return nullptr;
    env->SetIntArrayRegion(out, 0, 3, reinterpret_cast<jint*>(range));
    return out;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeSetVolumeDb256(
    JNIEnv*, jobject, jlong handle, jint uacVersion, jint unitId, jint acInterface, jint valueDb256) {
    NativeSession* session = fromHandle(handle);
    if (session == nullptr) return -1;
    std::lock_guard<std::mutex> lock(session->mutex);
    return session->device->setVolumeDb256(uacVersion, unitId, acInterface, valueDb256) ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeSetMute(
    JNIEnv*, jobject, jlong handle, jint uacVersion, jint unitId, jint acInterface, jboolean mute) {
    NativeSession* session = fromHandle(handle);
    if (session == nullptr) return -1;
    std::lock_guard<std::mutex> lock(session->mutex);
    return session->device->setMute(uacVersion, unitId, acInterface, mute == JNI_TRUE) ? 0 : -1;
}

JNIEXPORT jstring JNICALL
Java_com_theveloper_pixelplay_usbaudio_UsbAudioNative_nativeGetLastError(
    JNIEnv* env, jobject, jlong handle) {
    NativeSession* session = fromHandle(handle);
    if (session == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(session->mutex);
    const std::string error = session->device ? session->device->lastError() : "";
    if (error.empty()) return nullptr;
    return env->NewStringUTF(error.c_str());
}

}  // extern "C"
