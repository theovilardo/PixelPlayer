package com.theveloper.pixelplay.presentation.spotify.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.PixelPlayTheme
import com.theveloper.pixelplay.R
import dagger.hilt.android.AndroidEntryPoint
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape



@AndroidEntryPoint
class SpotifyLoginActivity : ComponentActivity() {

    private val viewModel: SpotifyLoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PixelPlayTheme {
                SpotifyLoginScreen(
                    viewModel = viewModel,
                    onClose = { finish() }
                )
            }
        }

        handleAuthIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
    }

    private fun handleAuthIntent(intent: Intent?) {
        val hasCallback = intent?.hasExtra(EXTRA_AUTH_CODE) == true ||
            intent?.hasExtra(EXTRA_AUTH_ERROR) == true
        if (!hasCallback) return

        viewModel.handleCallback(
            code = intent.getStringExtra(EXTRA_AUTH_CODE),
            state = intent.getStringExtra(EXTRA_AUTH_STATE),
            error = intent.getStringExtra(EXTRA_AUTH_ERROR)
        )
        intent.removeExtra(EXTRA_AUTH_CODE)
        intent.removeExtra(EXTRA_AUTH_STATE)
        intent.removeExtra(EXTRA_AUTH_ERROR)
    }

    companion object {
        const val EXTRA_AUTH_CODE = "spotify_auth_code"
        const val EXTRA_AUTH_STATE = "spotify_auth_state"
        const val EXTRA_AUTH_ERROR = "spotify_auth_error"
    }
}

@Composable
fun SpotifyLoginScreen(
    viewModel: SpotifyLoginViewModel,
    onClose: () -> Unit
) {
    val loginState by viewModel.state.collectAsStateWithLifecycle()

    SpotifyLoginContent(
        loginState = loginState,
        defaultRedirectUri = SpotifyAuthConstants.DEFAULT_REDIRECT_URI,
        onStartLogin = { clientId, redirectUri -> viewModel.startLogin(clientId, redirectUri) },
        onClearTransientState = { viewModel.clearTransientState() },
        onClose = onClose
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLoginContent(
    loginState: SpotifyLoginState,
    defaultRedirectUri: String,
    onStartLogin: (String, String) -> Unit,
    onClearTransientState: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var clientId by remember { mutableStateOf("") }
    var redirectUri by remember { mutableStateOf(defaultRedirectUri) }

    LaunchedEffect(loginState) {
        when (loginState) {
            is SpotifyLoginState.AwaitingBrowser -> {
                context.startActivity(Intent(Intent.ACTION_VIEW, loginState.authUri))
                onClearTransientState()
            }

            is SpotifyLoginState.Success -> {
                Toast.makeText(context, "Connected to ${loginState.displayName}", Toast.LENGTH_SHORT).show()
                onClose()
            }

            is SpotifyLoginState.Error -> {
                snackbarHostState.showSnackbar(loginState.message)
                onClearTransientState()
            }

            else -> Unit
        }
    }

    val isLoading = loginState is SpotifyLoginState.Loading
    val inputShape = AbsoluteSmoothCornerShape(18.dp, 60)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Spotify",
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    FilledIconButton(
                        modifier = Modifier.padding(start = 6.dp),
                        onClick = onClose,
                        enabled = !isLoading,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(28.dp))

            Box(
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_spotify),
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = Color(0xFF1ed760)
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "Connect Spotify",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Sync your Spotify library into PixelPlayer and control playback through Spotify.",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            Card(
                shape = AbsoluteSmoothCornerShape(20.dp, 60),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RowWithIcon(
                        icon = Icons.Rounded.LibraryMusic,
                        text = "Library access uses Spotify Web API scopes."
                    )
                    RowWithIcon(
                        icon = Icons.AutoMirrored.Rounded.Login,
                        text = "Playback remains controlled by Spotify and may require Premium."
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = AbsoluteSmoothCornerShape(28.dp, 60),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Developer app",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        singleLine = true,
                        label = { Text("Spotify client ID") },
                        leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                        shape = inputShape,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        colors = OutlinedTextFieldDefaults.colors()
                    )

                    OutlinedTextField(
                        value = redirectUri,
                        onValueChange = { redirectUri = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        singleLine = true,
                        label = { Text("Redirect URI") },
                        shape = inputShape,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                onStartLogin(clientId, redirectUri)
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors()
                    )

                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = !isLoading,
                        onClick = {
                            focusManager.clearFocus()
                            onStartLogin(clientId, redirectUri)
                        }
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Continue with Spotify")
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun RowWithIcon(
    icon: ImageVector,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Color(0xFF1ED760)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = GoogleSansRounded,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}



@Preview(showBackground = true)
@Composable
private fun SpotifyLoginPreview() {
    PixelPlayTheme {
        SpotifyLoginContent(
            loginState = SpotifyLoginState.Idle,
            defaultRedirectUri = SpotifyAuthConstants.DEFAULT_REDIRECT_URI,
            onStartLogin = { _, _ -> },
            onClearTransientState = {},
            onClose = {}
        )
    }
}

