# Vendored libusb

Upstream: https://github.com/libusb/libusb
Version: 1.0.30 (unmodified sources)
Source tarball: `libusb-1.0_1.0.30.orig.tar.bz2` (upstream release tarball as
mirrored by Debian: https://deb.debian.org/debian/pool/main/libu/libusb-1.0/)

Only the subset needed for the Android/Linux backend is vendored — the same
file list libusb's own `android/jni/libusb.mk` compiles:

- `libusb/{core,descriptor,hotplug,io,sync,strerror}.c` and public/private headers
- `libusb/os/{linux_usbfs,events_posix,threads_posix,linux_netlink}.c` and headers
- `android/config.h` (libusb's own Android build configuration)
- `COPYING` (LGPL-2.1 license text — libusb is built and shipped as a separate
  shared library, `libusb-1.0.so`)

To update: download the new upstream release tarball, replace the files above
1:1, and update the version in this file. Do not patch the sources; anything
Android-specific belongs in our CMakeLists.txt or our own code.
