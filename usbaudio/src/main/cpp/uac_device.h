#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

#include "libusb.h"

/**
 * Owns the libusb context/handle wrapped around an Android UsbDeviceConnection fd and
 * implements the USB Audio Class control-plane: kernel-driver detach + interface claim,
 * alt-setting selection, sample-rate programming (UAC1 endpoint / UAC2 clock source) and
 * feature-unit volume/mute.
 */
class UacDevice {
public:
    static std::unique_ptr<UacDevice> create(int fd, std::string* errorOut);
    ~UacDevice();

    UacDevice(const UacDevice&) = delete;
    UacDevice& operator=(const UacDevice&) = delete;

    bool claimInterfaces(int acInterface, int asInterface);
    bool setAltSetting(int asInterface, int altSetting);

    bool setSampleRateUac2(int clockId, int acInterface, uint32_t rateHz);
    bool setSampleRateUac1(int endpointAddress, uint32_t rateHz);

    /** UAC2 clock selector: route the clock tree through 1-based input [pin]. */
    bool setClockSelector(int selectorId, int acInterface, int pin);

    /** Generic class/standard IN control transfer for capability probing (post-claim). */
    int controlTransferIn(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index,
                          uint8_t* data, uint16_t length);

    /** Volume values are UAC-native: signed 1/256 dB steps. out = {min, max, res}. */
    bool getVolumeRangeDb256(int uacVersion, int unitId, int acInterface, int32_t out[3]);
    bool setVolumeDb256(int uacVersion, int unitId, int acInterface, int32_t valueDb256);
    bool setMute(int uacVersion, int unitId, int acInterface, bool mute);

    /** True when the bus enumerated the device at high speed (µframe timing). */
    bool isHighSpeed() const;

    libusb_context* context() const { return ctx_; }
    libusb_device_handle* handle() const { return handle_; }

    std::string lastError() const;
    void setLastError(const std::string& error);

private:
    UacDevice() = default;

    bool controlOut(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index,
                    const uint8_t* data, uint16_t length);
    int controlIn(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index,
                  uint8_t* data, uint16_t length);

    libusb_context* ctx_ = nullptr;
    libusb_device_handle* handle_ = nullptr;
    int claimedAc_ = -1;
    int claimedAs_ = -1;

    mutable std::mutex errorMutex_;
    std::string lastError_;
};
