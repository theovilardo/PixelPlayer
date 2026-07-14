package com.theveloper.pixelplay.presentation.spotify.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class SpotifyAuthCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val callbackUri = intent?.data
        val relayIntent = Intent(this, SpotifyLoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(SpotifyLoginActivity.EXTRA_AUTH_CODE, callbackUri?.getQueryParameter("code"))
            putExtra(SpotifyLoginActivity.EXTRA_AUTH_STATE, callbackUri?.getQueryParameter("state"))
            putExtra(SpotifyLoginActivity.EXTRA_AUTH_ERROR, callbackUri?.getQueryParameter("error"))
        }
        startActivity(relayIntent)
        finish()
    }
}

