package com.theveloper.pixelplay.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.ui.theme.LocalShowSongQualityBadge

@Immutable
enum class SongQuality(val label: String, val background: Color) {
    LQ("LQ", Color(0x4778909C)),   // #78909C @ ~28%
    HQ("HQ", Color(0xA84CAF50)),   // #1E88E5 @ ~28%
    SQ("SQ", Color(0xA31E88E5)),   // #F9A825 @ ~30%
    HR("HR", Color(0x8AFFC400));  // #8E24AA @ ~30%

    companion object {
        private val losslessFormats = setOf("flac", "alac", "wav", "aiff", "ape")

        fun from(song: Song): SongQuality? = from(
            mimeType = song.mimeType,
            bitrate = song.bitrate,
            sampleRate = song.sampleRate
        )

        fun from(mimeType: String?, bitrate: Int?, sampleRate: Int?): SongQuality? {
            if (mimeType.isNullOrBlank() && bitrate == null && sampleRate == null) return null

            if (sampleRate != null && sampleRate > 48_000) return HR

            val format = AudioMetaUtils.mimeTypeToFormat(mimeType)
            if (format in losslessFormats) return SQ

            if (bitrate != null && bitrate >= 256_000) return HQ

            return LQ
        }
    }
}

@Composable
fun SongQualityBadge(
    quality: SongQuality,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = quality.label,
        color = textColor,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.3.sp
        ),
        modifier = modifier
            .background(quality.background, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/** Call this in song list artist rows. No-op when toggle is off or quality unknown. */
@Composable
fun SongQualityBadgeIfEnabled(
    song: Song,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    if (!LocalShowSongQualityBadge.current) return
    val quality = SongQuality.from(song) ?: return
    SongQualityBadge(quality = quality, textColor = textColor, modifier = modifier)
}