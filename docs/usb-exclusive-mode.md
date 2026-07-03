# USB Exclusive Mode (bit-perfect USB DAC output)

Fully opt-in output mode: when enabled in **Settings → Playback → USB Audio** and a USB
DAC is attached, PixelPlay claims the DAC's USB Audio Class interface directly (libusb on
the file descriptor from `UsbDeviceConnection` — no root) and streams decoded PCM at the
track's native sample rate and bit depth, bypassing AudioTrack/AudioFlinger and its
resampling/mixing. With the toggle off, playback is byte-for-byte the pre-existing path.

## Architecture

```
FFmpeg / MediaCodec decoders (ExoPlayer renderers)
        │ PCM 16/24/32/float
        ▼
UsbAudioSink (Media3 AudioSink, app module)          ←  the ONE branch point:
        │ FormatNegotiator picks alt-setting + rate      DualPlayerEngine.buildAudioSink()
        │ PcmRepacker left-justifies into the DAC's      returns this instead of
        ▼ subslot (lossless on the bit-perfect path)     DefaultAudioSink while engaged
UsbAudioSession (:usbaudio Kotlin)
        │ JNI (direct ByteBuffers, non-blocking ring backpressure)
        ▼
Native driver (:usbaudio C++ / libusb 1.0.30, shared .so, LGPL-2.1)
  UacDevice   — wrap fd, detach kernel driver, claim AC+AS, alt settings,
                UAC1 endpoint / UAC2 clock-source sample-rate, feature-unit volume
  IsoStream   — isochronous OUT pipeline (8 transfers × 8 packets), Q16.16
                fractional packet sizing, explicit-feedback correction on async
                endpoints, silence-fill on underrun, alt-0 on stop
```

Control plane (all unit-tested, no hardware needed): `UsbDescriptorParser` builds a
`UacTopology` from `getRawDescriptors()`; `UacCapabilityProber` resolves UAC2 rates via a
clock-source RANGE request on EP0; `FormatNegotiator` picks the alt setting/rate. The
`UsbExclusiveModeController` state machine (`Disabled → NoDevice → PermissionPending →
Ready → Active`, plus `PermissionDenied`/`Error`) owns the session lifecycle; MusicService
collects it and swaps the engine's sink using the same rebuild-preserving-state path as
the Hi-Fi toggle.

## Bit-perfect policy (what we do and don't touch)

- **Never** on any path: dithering, volume scaling (`UsbAudioSink.setVolume` is a no-op),
  platform audio effects (no AudioTrack session exists — the equalizer cannot apply).
- **Bit-perfect** label: the only transformation is left-justified subslot packing —
  16→24/32-bit zero-padding, and float32→int (exact for material that was an integer of
  ≤24 bits, which is everything the FFmpeg float path produces from FLAC/ALAC ≤24-bit).
  Mono duplication into a stereo DAC also counts as bit-perfect (samples unchanged).
- **Converted** label (shown in the Now Playing badge as `source→output` and in the USB
  Audio settings): the DAC lacks the source rate (resampled via Sonic in 16-bit), has
  less depth (truncated, no dither), or fewer channels (5.1/7.1 Dolby downmix).
- Playback **speed/pitch is pinned to 1×** while exclusive (any tempo processing would
  not be bit-perfect); crossfade and ReplayGain (both volume scaling) are suspended and
  resume automatically when exclusive mode disengages. Track changes are plain gapless
  advances.

## Audio focus, calls and system sounds

Focus handling is unchanged (the engine's manual `AudioFocusRequest`): calls and alarms
**pause playback**; on focus return, playback resumes if it was a transient loss. There is
deliberately **no ducking** — ducking is volume scaling. While the DAC is claimed, other
apps and system sounds cannot open it; ringtones/notifications play on the phone's own
output. The deck/preview player likewise stays on the phone output while a DAC is claimed.

## Volume

If the DAC exposes a UAC feature unit with a master volume, the USB Audio settings screen
offers a **hardware volume** slider (SET CUR on the feature unit, in the DAC's own 1/256 dB
steps — the PCM samples are untouched). Otherwise the output is fixed at full scale and the
screen shows a one-time **line-level warning**. The phone's volume keys do not affect the
DAC in exclusive mode (there is no AudioTrack); mapping volume keys to the feature unit is
a possible follow-up via a Media3 device-volume override.

## Lifecycle & edge cases

- **Unplug mid-playback**: the driver reports the dead stream and the DETACHED broadcast
  fires; the service pauses, the session closes exactly once, and the engine rebuilds onto
  the normal output preserving queue/position. No crash, no lost queue.
- **Re-attach**: granted devices are remembered (`vendorId:productId:serial` in DataStore);
  permission is re-requested automatically and, if the device's *auto-resume* switch is on
  and playback was interrupted by the unplug, playback resumes.
- **Sample-rate switches between tracks** (44.1 kHz → 96 kHz): the sink reprograms the
  alt setting/clock during `configure()`; the stream starts in silence so the DAC's PLL
  locks before audio flows (no pops).
- **Background/doze**: the existing `mediaPlayback` foreground service and
  `WAKE_MODE_LOCAL` wake lock keep the USB stream alive with the screen off.
- **Media session/notification**: unchanged — the session wraps the engine's master
  player, and rebuilds are already propagated by the player-swap listeners.

## Known limitations (MVP)

- Async endpoints with *implicit* feedback (no explicit feedback endpoint) are paced at
  nominal rate; devices needing implicit feedback may drift on very long playback.
- DSD (DoP) is not implemented; `UacCapabilities.dsdSupport` is the hook.
- Only the first attached DAC is used when several are connected.
- Playback speed control is unavailable while exclusive mode is engaged.

## Hardware verification checklist

Everything above the JNI line is covered by JUnit tests (`:usbaudio:test`,
`:app:testDebugUnitTest` — parser blobs, negotiation table, repack golden vectors,
controller transition table, sink position/drain/backpressure). The following needs a
physical DAC (ideally one UAC2 async device with a rate display and one UAC1 dongle):

1. Attach DAC → permission dialog → grant → settings screen shows rates/depths.
2. Debug build: "Play test tone" at each advertised rate (driver bring-up, no ExoPlayer).
3. Play FLAC 16/44.1, 24/96, 24/192, WAV 24-bit, MP3, Opus — Now Playing shows
   `USB • …` and the DAC's own display shows the *source* rate (ground truth).
4. While playing, other apps cannot output to the DAC; system sounds stay on the phone.
5. Queue through 44.1 → 96 → 44.1 tracks: no pops at the switches.
6. Seek, notification controls, headset buttons all work; seekbar tracks correctly.
7. Unplug mid-song: playback pauses, app does not crash, normal output works.
8. Replug: permission re-grant (or remembered) → auto-resume if enabled.
9. Incoming call and alarm: playback pauses and resumes; no mixing into the DAC.
10. 1-hour screen-off playback: stream survives doze.
11. Hardware-volume DAC: slider changes loudness; fixed-volume DAC: warning shown.

## Licensing

libusb is vendored unmodified (see `usbaudio/src/main/cpp/external/libusb/VENDORED.md`)
and built as its own shared library, `libusb-1.0.so`, in keeping with LGPL-2.1. The
license text ships in the same directory (`COPYING`).
