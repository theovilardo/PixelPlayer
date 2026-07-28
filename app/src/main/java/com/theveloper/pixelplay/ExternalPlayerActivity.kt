package com.theveloper.pixelplay

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.components.external.ExternalPlayerOverlay
import com.theveloper.pixelplay.ui.theme.PixelPlayTheme
import android.content.Intent.EXTRA_STREAM
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.data.preferences.AppThemeMode
import com.theveloper.pixelplay.data.preferences.ThemePreferencesRepository
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class ExternalPlayerActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()
    @Inject
    lateinit var themePreferencesRepository: ThemePreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)

        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            val appThemeMode by themePreferencesRepository.appThemeModeFlow.collectAsStateWithLifecycle(initialValue = AppThemeMode.FOLLOW_SYSTEM)
            val useDarkTheme = when (appThemeMode) {
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
                else -> systemDarkTheme
            }
            PixelPlayTheme(darkTheme = useDarkTheme) {
                ExternalPlayerOverlay(
                    playerViewModel = playerViewModel,
                    onDismiss = { finish() },
                    onOpenFullPlayer = { openFullPlayer() }
                )
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when {
            intent.action == Intent.ACTION_VIEW && intent.data != null -> {
                intent.data?.let { uri ->
                    persistUriPermissionIfNeeded(intent, uri)
                    playerViewModel.playExternalUri(uri)
                }
                clearExternalIntentPayload(intent)
            }

            intent.action == Intent.ACTION_SEND && intent.type?.startsWith("audio/") == true -> {
                resolveStreamUri(intent)?.let { uri ->
                    persistUriPermissionIfNeeded(intent, uri)
                    playerViewModel.playExternalUri(uri)
                }
                clearExternalIntentPayload(intent)
            }
        }
    }

    private fun openFullPlayer() {
        val fullPlayerIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("ACTION_SHOW_PLAYER", true)
        }
        startActivity(fullPlayerIntent)
        finish()
    }

    private fun resolveStreamUri(intent: Intent): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_STREAM, Uri::class.java)?.let { return it }
        } else {
            @Suppress("DEPRECATION")
            val legacyUri = intent.getParcelableExtra<Uri>(EXTRA_STREAM)
            if (legacyUri != null) return legacyUri
        }

        intent.clipData?.let { clipData ->
            if (clipData.itemCount > 0) {
                return clipData.getItemAt(0).uri
            }
        }

        return intent.data
    }

    @Suppress("WrongConstant")
    private fun persistUriPermissionIfNeeded(intent: Intent, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val hasPersistablePermission = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
            if (hasPersistablePermission) {
                val takeFlags = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (takeFlags != 0) {
                    runCatching { contentResolver.takePersistableUriPermission(uri, takeFlags) }
                }
            }
        }
    }

    private fun clearExternalIntentPayload(intent: Intent) {
        intent.data = null
        intent.clipData = null
        intent.removeExtra(EXTRA_STREAM)
    }
}
