cmake_minimum_required(VERSION 3.22.1)
project(usbaudio LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- libusb (vendored, see external/libusb/VENDORED.md) -----------------------
# Built as a SHARED library: libusb is LGPL-2.1, dynamic linking keeps the
# app's obligations to shipping the license text and this rebuildable copy.
set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/external/libusb)

add_library(usb-1.0 SHARED
    ${LIBUSB_ROOT}/libusb/core.c
    ${LIBUSB_ROOT}/libusb/descriptor.c
    ${LIBUSB_ROOT}/libusb/hotplug.c
    ${LIBUSB_ROOT}/libusb/io.c
    ${LIBUSB_ROOT}/libusb/sync.c
    ${LIBUSB_ROOT}/libusb/strerror.c
    ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
    ${LIBUSB_ROOT}/libusb/os/events_posix.c
    ${LIBUSB_ROOT}/libusb/os/threads_posix.c
    ${LIBUSB_ROOT}/libusb/os/linux_netlink.c
)

# android/config.h must come first so libusb's `#include <config.h>` finds it.
target_include_directories(usb-1.0
    PRIVATE ${LIBUSB_ROOT}/android ${LIBUSB_ROOT}/libusb/os
    PUBLIC ${LIBUSB_ROOT}/libusb
)
target_compile_options(usb-1.0 PRIVATE -fvisibility=hidden -pthread)
target_link_libraries(usb-1.0 PRIVATE log)

# --- JNI bridge + driver --------------------------------------------------------
add_library(usbaudio_jni SHARED
    jni_bridge.cpp
    uac_device.cpp
    iso_stream.cpp
)

target_link_libraries(usbaudio_jni PRIVATE usb-1.0 log)
