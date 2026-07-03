#include "uac_device.h"

#include <cinttypes>

#include "log.h"

namespace {

// bmRequestType values
constexpr uint8_t kClassInterfaceOut = 0x21; // host→device | class | interface
constexpr uint8_t kClassInterfaceIn = 0xA1;  // device→host | class | interface
constexpr uint8_t kClassEndpointOut = 0x22;  // host→device | class | endpoint

// Shared request codes
constexpr uint8_t kReqCur = 0x01;
constexpr uint8_t kReqRange = 0x02; // UAC2 only
// UAC1 GET_MIN/GET_MAX/GET_RES
constexpr uint8_t kReqGetMin = 0x82;
constexpr uint8_t kReqGetMax = 0x83;
constexpr uint8_t kReqGetRes = 0x84;

// Control selectors
constexpr uint8_t kCsSamFreqControl = 0x01;   // clock source (UAC2) / endpoint (UAC1)
constexpr uint8_t kCxClockSelectorControl = 0x01; // clock selector pin (UAC2 §A.17.2)
constexpr uint8_t kFuMuteControl = 0x01;
constexpr uint8_t kFuVolumeControl = 0x02;

constexpr unsigned kControlTimeoutMs = 1000;
constexpr int kClaimRetries = 3;

}  // namespace

std::unique_ptr<UacDevice> UacDevice::create(int fd, std::string* errorOut) {
    std::unique_ptr<UacDevice> device(new UacDevice());

    // The Android USB host API hands us an already-open usbfs fd; libusb must not try to
    // enumerate /dev/bus/usb itself (no permission without root).
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);

    int rc = libusb_init(&device->ctx_);
    if (rc != LIBUSB_SUCCESS) {
        if (errorOut) *errorOut = std::string("libusb_init: ") + libusb_error_name(rc);
        return nullptr;
    }

    rc = libusb_wrap_sys_device(device->ctx_, static_cast<intptr_t>(fd), &device->handle_);
    if (rc != LIBUSB_SUCCESS || device->handle_ == nullptr) {
        if (errorOut) *errorOut = std::string("libusb_wrap_sys_device: ") + libusb_error_name(rc);
        return nullptr;
    }

    // Have libusb move the kernel's snd-usb-audio driver out of the way on claim and
    // hand the interface back on release.
    libusb_set_auto_detach_kernel_driver(device->handle_, 1);

    UA_LOGI("UacDevice created (fd=%d, highSpeed=%d)", fd, device->isHighSpeed() ? 1 : 0);
    return device;
}

UacDevice::~UacDevice() {
    if (handle_ != nullptr) {
        if (claimedAs_ >= 0) libusb_release_interface(handle_, claimedAs_);
        if (claimedAc_ >= 0) libusb_release_interface(handle_, claimedAc_);
        libusb_close(handle_);
    }
    if (ctx_ != nullptr) {
        libusb_exit(ctx_);
    }
}

bool UacDevice::claimInterfaces(int acInterface, int asInterface) {
    struct Claim {
        int number;
        int* slot;
    } claims[] = {{acInterface, &claimedAc_}, {asInterface, &claimedAs_}};

    for (const auto& claim : claims) {
        if (*claim.slot == claim.number) continue; // already claimed
        int rc = LIBUSB_ERROR_OTHER;
        for (int attempt = 0; attempt < kClaimRetries; ++attempt) {
            rc = libusb_claim_interface(handle_, claim.number);
            if (rc == LIBUSB_SUCCESS) break;
            if (rc != LIBUSB_ERROR_BUSY) break;
            // The audio HAL may still hold the interface right after permission grant.
            libusb_detach_kernel_driver(handle_, claim.number);
        }
        if (rc != LIBUSB_SUCCESS) {
            setLastError(std::string("claim interface ") + std::to_string(claim.number) + ": " +
                         libusb_error_name(rc));
            UA_LOGE("%s", lastError().c_str());
            return false;
        }
        *claim.slot = claim.number;
    }
    UA_LOGI("Claimed AC=%d AS=%d", acInterface, asInterface);
    return true;
}

bool UacDevice::setAltSetting(int asInterface, int altSetting) {
    int rc = libusb_set_interface_alt_setting(handle_, asInterface, altSetting);
    if (rc != LIBUSB_SUCCESS) {
        setLastError(std::string("set_interface_alt_setting(") + std::to_string(asInterface) +
                     ", " + std::to_string(altSetting) + "): " + libusb_error_name(rc));
        UA_LOGE("%s", lastError().c_str());
        return false;
    }
    return true;
}

bool UacDevice::setSampleRateUac2(int clockId, int acInterface, uint32_t rateHz) {
    const uint8_t data[4] = {
        static_cast<uint8_t>(rateHz & 0xFF),
        static_cast<uint8_t>((rateHz >> 8) & 0xFF),
        static_cast<uint8_t>((rateHz >> 16) & 0xFF),
        static_cast<uint8_t>((rateHz >> 24) & 0xFF),
    };
    const uint16_t value = static_cast<uint16_t>(kCsSamFreqControl << 8);
    const uint16_t index = static_cast<uint16_t>((clockId << 8) | (acInterface & 0xFF));
    if (!controlOut(kClassInterfaceOut, kReqCur, value, index, data, sizeof(data))) {
        return false;
    }
    UA_LOGI("UAC2 clock %d set to %" PRIu32 " Hz", clockId, rateHz);
    return true;
}

bool UacDevice::setSampleRateUac1(int endpointAddress, uint32_t rateHz) {
    const uint8_t data[3] = {
        static_cast<uint8_t>(rateHz & 0xFF),
        static_cast<uint8_t>((rateHz >> 8) & 0xFF),
        static_cast<uint8_t>((rateHz >> 16) & 0xFF),
    };
    const uint16_t value = static_cast<uint16_t>(kCsSamFreqControl << 8);
    const uint16_t index = static_cast<uint16_t>(endpointAddress & 0xFF);
    if (!controlOut(kClassEndpointOut, kReqCur, value, index, data, sizeof(data))) {
        return false;
    }
    UA_LOGI("UAC1 endpoint 0x%02x set to %" PRIu32 " Hz", endpointAddress, rateHz);
    return true;
}

bool UacDevice::setClockSelector(int selectorId, int acInterface, int pin) {
    const uint8_t data[1] = {static_cast<uint8_t>(pin & 0xFF)};
    const uint16_t value = static_cast<uint16_t>(kCxClockSelectorControl << 8);
    const uint16_t index = static_cast<uint16_t>((selectorId << 8) | (acInterface & 0xFF));
    if (!controlOut(kClassInterfaceOut, kReqCur, value, index, data, sizeof(data))) {
        return false;
    }
    UA_LOGI("Clock selector %d set to pin %d", selectorId, pin);
    return true;
}

int UacDevice::controlTransferIn(uint8_t requestType, uint8_t request, uint16_t value,
                                 uint16_t index, uint8_t* data, uint16_t length) {
    return controlIn(requestType, request, value, index, data, length);
}

bool UacDevice::getVolumeRangeDb256(int uacVersion, int unitId, int acInterface, int32_t out[3]) {
    const uint16_t value = static_cast<uint16_t>(kFuVolumeControl << 8); // channel 0 = master
    const uint16_t index = static_cast<uint16_t>((unitId << 8) | (acInterface & 0xFF));

    if (uacVersion == 2) {
        // RANGE: wNumSubRanges + N × (wMIN, wMAX, wRES); we use the first subrange.
        uint8_t buffer[2 + 3 * 2] = {0};
        int read = controlIn(kClassInterfaceIn, kReqRange, value, index, buffer, sizeof(buffer));
        if (read < static_cast<int>(sizeof(buffer))) return false;
        const int count = buffer[0] | (buffer[1] << 8);
        if (count < 1) return false;
        out[0] = static_cast<int16_t>(buffer[2] | (buffer[3] << 8));
        out[1] = static_cast<int16_t>(buffer[4] | (buffer[5] << 8));
        out[2] = static_cast<int16_t>(buffer[6] | (buffer[7] << 8));
        return true;
    }

    const uint8_t requests[3] = {kReqGetMin, kReqGetMax, kReqGetRes};
    for (int i = 0; i < 3; ++i) {
        uint8_t buffer[2] = {0};
        int read = controlIn(kClassInterfaceIn, requests[i], value, index, buffer, sizeof(buffer));
        if (read < 2) return false;
        out[i] = static_cast<int16_t>(buffer[0] | (buffer[1] << 8));
    }
    return true;
}

bool UacDevice::setVolumeDb256(int uacVersion, int unitId, int acInterface, int32_t valueDb256) {
    (void)uacVersion; // SET CUR encoding is identical for UAC1 and UAC2 (2-byte value)
    const int16_t clamped = static_cast<int16_t>(valueDb256);
    const uint8_t data[2] = {
        static_cast<uint8_t>(clamped & 0xFF),
        static_cast<uint8_t>((clamped >> 8) & 0xFF),
    };
    const uint16_t value = static_cast<uint16_t>(kFuVolumeControl << 8);
    const uint16_t index = static_cast<uint16_t>((unitId << 8) | (acInterface & 0xFF));
    return controlOut(kClassInterfaceOut, kReqCur, value, index, data, sizeof(data));
}

bool UacDevice::setMute(int uacVersion, int unitId, int acInterface, bool mute) {
    (void)uacVersion;
    const uint8_t data[1] = {static_cast<uint8_t>(mute ? 1 : 0)};
    const uint16_t value = static_cast<uint16_t>(kFuMuteControl << 8);
    const uint16_t index = static_cast<uint16_t>((unitId << 8) | (acInterface & 0xFF));
    return controlOut(kClassInterfaceOut, kReqCur, value, index, data, sizeof(data));
}

bool UacDevice::isHighSpeed() const {
    libusb_device* device = libusb_get_device(handle_);
    if (device == nullptr) return false;
    return libusb_get_device_speed(device) >= LIBUSB_SPEED_HIGH;
}

std::string UacDevice::lastError() const {
    std::lock_guard<std::mutex> lock(errorMutex_);
    return lastError_;
}

void UacDevice::setLastError(const std::string& error) {
    std::lock_guard<std::mutex> lock(errorMutex_);
    lastError_ = error;
}

bool UacDevice::controlOut(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index,
                           const uint8_t* data, uint16_t length) {
    int rc = libusb_control_transfer(handle_, requestType, request, value, index,
                                     const_cast<uint8_t*>(data), length, kControlTimeoutMs);
    if (rc < 0) {
        setLastError(std::string("control OUT req=0x") + std::to_string(request) + ": " +
                     libusb_error_name(rc));
        UA_LOGW("%s", lastError().c_str());
        return false;
    }
    return true;
}

int UacDevice::controlIn(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index,
                         uint8_t* data, uint16_t length) {
    int rc = libusb_control_transfer(handle_, requestType, request, value, index, data, length,
                                     kControlTimeoutMs);
    if (rc < 0) {
        setLastError(std::string("control IN req=0x") + std::to_string(request) + ": " +
                     libusb_error_name(rc));
        UA_LOGW("%s", lastError().c_str());
    }
    return rc;
}
