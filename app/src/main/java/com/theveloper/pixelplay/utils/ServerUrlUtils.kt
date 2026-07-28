package com.theveloper.pixelplay.utils

import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrlUtils {

    data class NormalizedUrl(
        val url: HttpUrl,
        val original: String,
        val validationError: String? = null
    )

    fun normalizeUrlOrNull(raw: String): HttpUrl? {
        val trimmed = raw.trim().trimEnd('/')
        val withScheme = if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            "https://$trimmed"
        } else {
            trimmed
        }
        return withScheme.toHttpUrlOrNull()
    }

    fun normalizeUrlString(raw: String): String =
        normalizeUrlOrNull(raw)?.toString()?.trimEnd('/') ?: raw.trim().trimEnd('/')

    fun validateConnectionUrl(parsed: HttpUrl): String? {
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return "Server URL must not contain embedded credentials"
        }
        return null
    }

    fun requireSecureForRemote(parsed: HttpUrl, service: String = "server"): String? {
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return "Server URL must not contain embedded credentials"
        }
        if (!parsed.isHttps && !CloudStreamSecurity.isLocalOrPrivateHost(parsed.host)) {
            return "Use https:// for remote $service servers. HTTP is only allowed for local network addresses."
        }
        return null
    }
}
