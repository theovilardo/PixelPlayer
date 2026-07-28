package com.theveloper.pixelplay.presentation.model

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.theveloper.pixelplay.R

enum class SettingsCategory(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val icon: ImageVector? = null,
    val iconRes: Int? = null
) {
    LIBRARY(
        id = "library",
        titleRes = R.string.settings_category_music_management_title,
        subtitleRes = R.string.settings_category_music_management_subtitle,
        icon = Icons.Rounded.LibraryMusic
    ),
    APPEARANCE(
        id = "appearance",
        titleRes = R.string.settings_category_appearance_title,
        subtitleRes = R.string.settings_category_appearance_subtitle,
        icon = Icons.Rounded.Palette
    ),
    LYRICS(
        id = "lyrics",
        titleRes = R.string.settings_category_lyrics_title,
        subtitleRes = R.string.settings_category_lyrics_subtitle,
        icon = Icons.Rounded.QueueMusic
    ),
    PLAYBACK(
        id = "playback",
        titleRes = R.string.settings_category_playback_title,
        subtitleRes = R.string.settings_category_playback_subtitle,
        icon = Icons.Rounded.MusicNote
    ),
    EQUALIZER(
        id = "equalizer",
        titleRes = R.string.settings_category_equalizer_title,
        subtitleRes = R.string.settings_category_equalizer_subtitle,
        icon = Icons.Rounded.GraphicEq
    ),
    BEHAVIOR(
        id = "behavior",
        titleRes = R.string.settings_category_behavior_title,
        subtitleRes = R.string.settings_category_behavior_subtitle,
        iconRes = R.drawable.rounded_touch_app_24
    ),
    AI_INTEGRATION(
        id = "ai",
        titleRes = R.string.settings_category_ai_title,
        subtitleRes = R.string.settings_category_ai_subtitle,
        iconRes = R.drawable.gemini_ai
    ),
    GENERATION_PARAMETERS(
        id = "generation_parameters",
        titleRes = R.string.settings_category_generation_parameters_title,
        subtitleRes = R.string.settings_category_generation_parameters_subtitle,
        icon = Icons.Rounded.Tune
    ),
    BACKUP_RESTORE(
        id = "backup_restore",
        titleRes = R.string.settings_category_backup_title,
        subtitleRes = R.string.settings_category_backup_subtitle,
        iconRes = R.drawable.rounded_upload_file_24
    ),
    DEVELOPER(
        id = "developer",
        titleRes = R.string.settings_category_developer_title,
        subtitleRes = R.string.settings_category_developer_subtitle,
        icon = Icons.Rounded.DeveloperMode
    ),
    DEVICE_CAPABILITIES(
        id = "device_capabilities",
        titleRes = R.string.settings_category_device_capabilities_title,
        subtitleRes = R.string.settings_category_device_capabilities_subtitle,
        icon = Icons.Rounded.DeveloperBoard
    ),
    ABOUT(
        id = "about",
        titleRes = R.string.settings_category_about_title,
        subtitleRes = R.string.settings_category_about_subtitle,
        icon = Icons.Rounded.Info
    );

    companion object {
        fun fromId(id: String): SettingsCategory? = entries.find { it.id == id }
    }


}
