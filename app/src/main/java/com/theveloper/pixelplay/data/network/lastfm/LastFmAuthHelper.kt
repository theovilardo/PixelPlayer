package com.theveloper.pixelplay.data.network.lastfm

import java.security.MessageDigest

/**
 * Builds the api_sig parameter required by all Last.fm write calls.
 *
 * Algorithm per https://www.last.fm/api/authspec (Section 8):
 * 1. Collect all call parameters EXCEPT `format` and `callback`.
 * 2. Sort them alphabetically by key.
 * 3. Concatenate as "key1value1key2value2...".
 * 4. Append the shared secret.
 * 5. MD5-hash the resulting UTF-8 string.
 *
 * @param params  The full set of request parameters (excluding format/callback).
 * @param secret  The application shared secret from last.fm/api/accounts.
 * @return 32-character lowercase hex MD5 digest.
 */
fun buildLastFmSignature(params: Map<String, String>, secret: String): String {
    val sorted = params
        .filterKeys { it != "format" && it != "callback" }
        .entries
        .sortedBy { it.key }

    val sigString = buildString {
        for ((k, v) in sorted) {
            append(k)
            append(v)
        }
        append(secret)
    }

    val md = MessageDigest.getInstance("MD5")
    val bytes = md.digest(sigString.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
