package com.theveloper.pixelplay.data

/**
 * Coarse "how much CPU headroom does this watch have" signal, computed once per process.
 *
 * Wear OS SoC core count tracks chip generation closely: entry/mid chips (e.g. the dual-core
 * Exynos W920/W930) ship with 2 Cortex-A55 cores, while newer/higher-tier chips (Snapdragon W5+,
 * Exynos W1000) ship with 4. On-device profiling on a 2-core watch showed a continuous per-frame
 * Compose animation alone was enough to saturate the main thread and starve ExoPlayer's
 * decode/render threads of the CPU they needed, causing audible playback stutter. Non-essential
 * continuous UI work should check this before running unconditionally.
 */
object WearDeviceTier {
    private const val CAPABLE_CORE_THRESHOLD = 4

    /** True when the device has enough CPU headroom to afford non-essential continuous UI work. */
    val isCapable: Boolean by lazy {
        Runtime.getRuntime().availableProcessors() >= CAPABLE_CORE_THRESHOLD
    }
}
