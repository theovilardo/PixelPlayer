package com.theveloper.pixelplay.presentation.navigation

import DelimiterConfigScreen
import com.theveloper.pixelplay.presentation.screens.WordDelimiterConfigScreen
import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.theveloper.pixelplay.R
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.theveloper.pixelplay.data.preferences.LaunchTab
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.presentation.screens.AlbumDetailScreen
import com.theveloper.pixelplay.presentation.screens.AccountsScreen
import com.theveloper.pixelplay.presentation.screens.ArtistDetailScreen
import com.theveloper.pixelplay.presentation.screens.ArtistSettingsScreen
import com.theveloper.pixelplay.presentation.screens.DailyMixScreen
import com.theveloper.pixelplay.presentation.screens.EditTransitionScreen
import com.theveloper.pixelplay.presentation.screens.EasterEggScreen
import com.theveloper.pixelplay.presentation.screens.ExperimentalSettingsScreen
import com.theveloper.pixelplay.presentation.screens.GenreDetailScreen
import com.theveloper.pixelplay.presentation.screens.HomeScreen
import com.theveloper.pixelplay.presentation.screens.LibraryScreen
import com.theveloper.pixelplay.presentation.screens.MashupScreen
import com.theveloper.pixelplay.presentation.screens.NavBarCornerRadiusScreen
import com.theveloper.pixelplay.presentation.screens.PaletteStyleSettingsScreen
import com.theveloper.pixelplay.presentation.screens.PlaylistDetailScreen
import com.theveloper.pixelplay.presentation.screens.RecentlyPlayedScreen

import com.theveloper.pixelplay.presentation.screens.AboutScreen
import com.theveloper.pixelplay.presentation.screens.OpenSourceLicensesScreen
import com.theveloper.pixelplay.presentation.screens.SearchScreen
import com.theveloper.pixelplay.presentation.screens.StatsScreen
import com.theveloper.pixelplay.presentation.screens.SettingsScreen
import com.theveloper.pixelplay.presentation.screens.SettingsCategoryScreen
import com.theveloper.pixelplay.presentation.screens.EqualizerScreen
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import kotlinx.coroutines.flow.first
import com.theveloper.pixelplay.presentation.components.ScreenWrapper

@OptIn(UnstableApi::class)
@SuppressLint("UnrememberedGetBackStackEntry")
@Composable
fun AppNavigation(
    playerViewModel: PlayerViewModel,
    navController: NavHostController,
    paddingValues: PaddingValues,
    userPreferencesRepository: UserPreferencesRepository,
    onSearchBarActiveChange: (Boolean) -> Unit,
    onOpenSidebar: () -> Unit
) {
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        startDestination = userPreferencesRepository.launchTabFlow
            .first()
            .toRoute()
    }

    startDestination?.let { initialRoute ->
        NavHost(
            navController = navController,
            startDestination = initialRoute,
            enterTransition = { aospSharedAxisEnter() },
            exitTransition = { aospSharedAxisExit() },
            popEnterTransition = { aospSharedAxisPopEnter() },
            popExitTransition = { aospSharedAxisPopExit() }
        ) {
            composable(
                Screen.Home.route,
                enterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = enterTransition()
                    )
                },
                exitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = exitTransition()
                    )
                },
                popEnterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popEnterTransition()
                    )
                },
                popExitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popExitTransition()
                    )
                },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    HomeScreen(
                        navController = navController, 
                        paddingValuesParent = paddingValues, 
                        playerViewModel = playerViewModel,
                        onOpenSidebar = onOpenSidebar
                    )
                }
            }
            composable(
                Screen.Search.route,
                enterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = enterTransition()
                    )
                },
                exitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = exitTransition()
                    )
                },
                popEnterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popEnterTransition()
                    )
                },
                popExitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popExitTransition()
                    )
                },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    SearchScreen(
                        paddingValues = paddingValues,
                        playerViewModel = playerViewModel,
                        navController = navController,
                        onSearchBarActiveChange = onSearchBarActiveChange
                    )
                }
            }
            composable(
                Screen.Library.route,
                enterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = enterTransition()
                    )
                },
                exitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = exitTransition()
                    )
                },
                popEnterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popEnterTransition()
                    )
                },
                popExitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popExitTransition()
                    )
                },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    LibraryScreen(navController = navController, playerViewModel = playerViewModel)
                }
            }
            composable(
                Screen.Settings.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    SettingsScreen(
                        navController = navController,
                        playerViewModel = playerViewModel,
                        onNavigationIconClick = {
                            navController.popBackStack()
                        }
                    )
                }
            }
            composable(
                Screen.Accounts.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    AccountsScreen(
                        onBackClick = { navController.popBackStack() },
                        onOpenNeteaseDashboard = {
                            navController.navigateSafely(Screen.NeteaseDashboard.route)
                        },
                        onOpenQqMusicDashboard = {
                            navController.navigateSafely(Screen.QqMusicDashboard.route)
                        },
                        onOpenNavidromeDashboard = {
                            navController.navigateSafely(Screen.NavidromeDashboard.route)
                        },
                        onOpenJellyfinDashboard = {
                            navController.navigateSafely(Screen.JellyfinDashboard.route)
                        }
                    )
                }
            }
            composable(
                route = Screen.SettingsCategory.route,
                arguments = listOf(navArgument("categoryId") { type = NavType.StringType }),
            ) { backStackEntry ->
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    val categoryId = backStackEntry.arguments?.getString("categoryId")
                    if (categoryId != null) {
                        SettingsCategoryScreen(
                            categoryId = categoryId,
                            navController = navController,
                            playerViewModel = playerViewModel,
                            onBackClick = { navController.popBackStack() }
                        )
                    }
                }
            }
            composable(
                Screen.PaletteStyle.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    PaletteStyleSettingsScreen(
                        playerViewModel = playerViewModel,
                        onBackClick = { navController.popBackStack() }
                    )
                }
            }
            composable(
                Screen.Experimental.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    ExperimentalSettingsScreen(
                        navController = navController,
                        playerViewModel = playerViewModel,
                        onNavigationIconClick = { navController.popBackStack() }
                    )
                }
            }
            composable(
                Screen.DailyMixScreen.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    DailyMixScreen(
                        playerViewModel = playerViewModel,
                        navController = navController
                    )
                }
            }
            composable(
                Screen.RecentlyPlayed.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    RecentlyPlayedScreen(
                        playerViewModel = playerViewModel,
                        navController = navController
                    )
                }
            }
            composable(
                Screen.Stats.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    StatsScreen(
                        navController = navController
                    )
                }
            }
            composable(
                route = Screen.PlaylistDetail.route,
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getString("playlistId")
                val playlistViewModel: PlaylistViewModel = hiltViewModel()
                if (playlistId != null) {
                    ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                        PlaylistDetailScreen(
                            playlistId = playlistId,
                            playerViewModel = playerViewModel,
                            playlistViewModel = playlistViewModel,
                            onBackClick = { navController.popBackStack() },
                            onDeletePlayListClick = { navController.popBackStack() },
                            navController = navController
                        )
                    }
                }
            }

            composable(
                Screen.DJSpace.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    MashupScreen()
                }
            }
            composable(
                route = Screen.GenreDetail.route,
                arguments = listOf(navArgument("genreId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val genreId = backStackEntry.arguments?.getString("genreId")
                if (genreId != null) {
                    ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                        GenreDetailScreen(
                            navController = navController,
                            genreId = genreId,
                            playerViewModel = playerViewModel
                        )
                    }
                } else {
                    Text(stringResource(R.string.nav_error_genre_id_missing), modifier = Modifier)
                }
            }
            composable(
                route = Screen.AlbumDetail.route,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getString("albumId")
                if (albumId != null) {
                    ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                        AlbumDetailScreen(
                            albumId = albumId,
                            navController = navController,
                            playerViewModel = playerViewModel
                        )
                    }
                }
            }
            composable(
                route = Screen.ArtistDetail.route,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val artistId = backStackEntry.arguments?.getString("artistId")
                if (artistId != null) {
                    ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                        ArtistDetailScreen(
                            artistId = artistId,
                            navController = navController,
                            playerViewModel = playerViewModel
                        )
                    }
                }
            }
            composable(
                "nav_bar_corner_radius",
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    NavBarCornerRadiusScreen(navController)
                }
            }
            composable(
                route = Screen.EditTransition.route,
                arguments = listOf(navArgument("playlistId") {
                    type = NavType.StringType
                    nullable = true
                }),
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    EditTransitionScreen(navController = navController)
                }
            }
            composable(
                Screen.About.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    AboutScreen(
                        navController = navController,
                        viewModel = playerViewModel,
                        onNavigationIconClick = { navController.popBackStack() }
                    )
                }
            }
            composable(
                Screen.OpenSourceLicenses.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    OpenSourceLicensesScreen(
                        onBackClick = { navController.popBackStack() },
                    )
                }
            }
            composable(
                Screen.EasterEgg.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    EasterEggScreen(
                        viewModel = playerViewModel,
                        onNavigationIconClick = { navController.popBackStack() },
                    )
                }
            }
            composable(
                Screen.ArtistSettings.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    ArtistSettingsScreen(navController = navController)
                }
            }
            composable(
                Screen.DelimiterConfig.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    DelimiterConfigScreen(navController = navController)
                }
            }
            composable(
                Screen.WordDelimiterConfig.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    WordDelimiterConfigScreen(navController = navController)
                }
            }
            composable(
                Screen.Equalizer.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    EqualizerScreen(
                        navController = navController,
                        playerViewModel = playerViewModel
                    )
                }
            }
            composable(
                Screen.DeviceCapabilities.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    com.theveloper.pixelplay.presentation.screens.DeviceCapabilitiesScreen(
                        navController = navController,
                        playerViewModel = playerViewModel
                    )
                }
            }
            composable(
                Screen.NeteaseDashboard.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    com.theveloper.pixelplay.presentation.netease.dashboard.NeteaseDashboardScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(
                Screen.QqMusicDashboard.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    com.theveloper.pixelplay.presentation.qqmusic.dashboard.QqMusicDashboardScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(
                Screen.NavidromeDashboard.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    com.theveloper.pixelplay.presentation.navidrome.dashboard.NavidromeDashboardScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(
                Screen.JellyfinDashboard.route,
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel, animatedVisibilityScope = this) {
                    com.theveloper.pixelplay.presentation.jellyfin.dashboard.JellyfinDashboardScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

private fun String.toRoute(): String = when (this) {
    LaunchTab.SEARCH -> Screen.Search.route
    LaunchTab.LIBRARY -> Screen.Library.route
    else -> Screen.Home.route
}

private enum class MainRootDirection {
    FORWARD,
    BACKWARD
}

// Base duration for bottom-nav switches at 1x — at 0.5x system scale = ~190 ms.
private const val BOTTOM_NAV_TRANSITION_DURATION = 380

// MD3 Expressive easing for bottom-nav switches
private val BottomNavEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private val MAIN_ROOT_TRANSITION_SPEC =
    tween<IntOffset>(durationMillis = BOTTOM_NAV_TRANSITION_DURATION, easing = BottomNavEasing)

private val MAIN_ROOT_FADE_SPEC =
    tween<Float>(durationMillis = BOTTOM_NAV_TRANSITION_DURATION / 2, easing = BottomNavEasing)

private fun mainRootDirection(
    fromRoute: String?,
    toRoute: String?
): MainRootDirection? {
    val fromIndex = mainRootRouteIndex(fromRoute) ?: return null
    val toIndex = mainRootRouteIndex(toRoute) ?: return null
    if (fromIndex == toIndex) return null
    return if (toIndex > fromIndex) MainRootDirection.FORWARD else MainRootDirection.BACKWARD
}

private fun mainRootEnterTransition(
    fromRoute: String?,
    toRoute: String?,
    fallback: EnterTransition
): EnterTransition = when (mainRootDirection(fromRoute, toRoute)) {
    MainRootDirection.FORWARD -> {
        slideInHorizontally(
            animationSpec = MAIN_ROOT_TRANSITION_SPEC,
            initialOffsetX = { (it * 0.5f).toInt() }
        ) + fadeIn(animationSpec = MAIN_ROOT_FADE_SPEC)
    }
    MainRootDirection.BACKWARD -> {
        slideInHorizontally(
            animationSpec = MAIN_ROOT_TRANSITION_SPEC,
            initialOffsetX = { -(it * 0.5f).toInt() }
        ) + fadeIn(animationSpec = MAIN_ROOT_FADE_SPEC)
    }
    null -> fallback
}

private fun mainRootExitTransition(
    fromRoute: String?,
    toRoute: String?,
    fallback: ExitTransition
): ExitTransition = when (mainRootDirection(fromRoute, toRoute)) {
    MainRootDirection.FORWARD -> {
        slideOutHorizontally(
            animationSpec = MAIN_ROOT_TRANSITION_SPEC,
            targetOffsetX = { -(it * 0.5f).toInt() }
        ) + fadeOut(animationSpec = MAIN_ROOT_FADE_SPEC)
    }
    MainRootDirection.BACKWARD -> {
        slideOutHorizontally(
            animationSpec = MAIN_ROOT_TRANSITION_SPEC,
            targetOffsetX = { (it * 0.5f).toInt() }
        ) + fadeOut(animationSpec = MAIN_ROOT_FADE_SPEC)
    }
    null -> fallback
}
