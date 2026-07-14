package com.theveloper.pixelplay.data.preferences

import android.content.Context
import androidx.annotation.StringRes
import com.theveloper.pixelplay.R

enum class AppLanguage(val tag: String, @StringRes val labelRes: Int) {
    SYSTEM("", R.string.settings_language_system),
    ENGLISH("en", R.string.settings_language_english),
    GERMAN("de", R.string.settings_language_german),
    SPANISH("es", R.string.settings_language_spanish),
    FRENCH("fr", R.string.settings_language_french),
    INDONESIAN("in", R.string.settings_language_indonesian),
    ITALIAN("it", R.string.settings_language_italian),
    KOREAN("ko", R.string.settings_language_korean),
    NORWEGIAN_BOKMAL("nb", R.string.settings_language_norwegian_bokmal),
    RUSSIAN("ru", R.string.settings_language_russian),
    SIMPLIFIED_CHINESE("zh-CN", R.string.settings_language_chinese),
    JAPANESE("ja", R.string.settings_language_japanese),
    TURKISH("tr", R.string.settings_language_turkish);

    companion object {
        val supportedLanguageTags: Set<String> = values().map { it.tag }.toSet()

        fun getLanguageOptions(context: Context): Map<String, String> {
            val systemOption = SYSTEM.tag to context.getString(SYSTEM.labelRes)
            val otherOptions = values()
                .filter { it != SYSTEM }
                .map { it.tag to context.getString(it.labelRes) }
                .sortedBy { it.second.lowercase() }

            val result = LinkedHashMap<String, String>()
            result[systemOption.first] = systemOption.second
            for (option in otherOptions) {
                result[option.first] = option.second
            }
            return result
        }

        fun normalize(languageTag: String?): String {
            val normalized = languageTag?.trim() ?: return SYSTEM.tag
            return values().find { it.tag.equals(normalized, ignoreCase = true) }?.tag ?: SYSTEM.tag
        }
    }
}
