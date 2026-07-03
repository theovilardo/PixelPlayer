package com.theveloper.pixelplay.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.theveloper.pixelplay.presentation.screens.BrowseScreen
import com.theveloper.pixelplay.presentation.screens.DownloadsScreen
import com.theveloper.pixelplay.presentation.screens.LibraryListScreen
import com.theveloper.pixelplay.presentation.screens.LocalPlaylistDetailScreen
import com.theveloper.pixelplay.presentation.screens.LocalPlaylistsScreen
import com.theveloper.pixelplay.presentation.screens.MoreScreen
import com.theveloper.pixelplay.presentation.screens.OutputScreen
import com.theveloper.pixelplay.presentation.screens.PlayerScreen
import com.theveloper.pixelplay.presentation.screens.QueueScreen
import com.theveloper.pixelplay.presentation.screens.SongListScreen
import com.theveloper.pixelplay.presentation.screens.TimerScreen
import com.theveloper.pixelplay.presentation.screens.VolumeScreen
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Navigation host for the Wear OS app.
 * Routes:
 * - PLAYER: Main player controls (start destination)
 * - VOLUME: Volume control
 * - BROWSE: Library category picker
 * - LIBRARY_LIST: List of albums/artists/playlists
 * - SONG_LIST: Songs within a context (album/artist/playlist/favorites/all)
 */
object WearScreens {
    const val PLAYER = "player"
    const val VOLUME = "volume"
    const val OUTPUT = "output"
    const val MORE = "more"
    const val QUEUE = "queue"
    const val TIMER = "timer"
    const val BROWSE = "browse"
    const val DOWNLOADS = "downloads"
    const val LIBRARY_LIST = "library_list/{browseType}/{title}"
    const val SONG_LIST = "song_list/{browseType}/{contextId}/{title}"
    const val LOCAL_PLAYLISTS = "local_playlists"
    const val LOCAL_PLAYLIST_DETAIL = "local_playlist_detail/{playlistId}/{name}"

    fun libraryListRoute(browseType: String, title: String): String {
        return "library_list/$browseType/${URLEncoder.encode(title, "UTF-8")}"
    }

    fun songListRoute(browseType: String, contextId: String, title: String): String {
        return "song_list/$browseType/$contextId/${URLEncoder.encode(title, "UTF-8")}"
    }

    fun localPlaylistDetailRoute(playlistId: String, name: String): String {
        return "local_playlist_detail/$playlistId/${URLEncoder.encode(name, "UTF-8")}"
    }
}

@Composable
fun WearNavigation() {
    val navController = rememberSwipeDismissableNavController()
    val navigateToBrowseCategory: (browseType: String, title: String) -> Unit = { browseType, title ->
        when (browseType) {
            "downloads" -> {
                navController.navigate(WearScreens.DOWNLOADS)
            }
            "favorites", "all_songs" -> {
                navController.navigate(
                    WearScreens.songListRoute(browseType, "none", title)
                )
            }
            else -> {
                navController.navigate(
                    WearScreens.libraryListRoute(browseType, title)
                )
            }
        }
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = WearScreens.PLAYER,
    ) {
        composable(WearScreens.PLAYER) {
            PlayerScreen(
                onBrowseCategoryClick = navigateToBrowseCategory,
                onVolumeClick = {
                    navController.navigate(WearScreens.VOLUME) {
                        launchSingleTop = true
                    }
                },
                onOutputClick = {
                    navController.navigate(WearScreens.OUTPUT) {
                        launchSingleTop = true
                    }
                },
                onMoreClick = {
                    navController.navigate(WearScreens.MORE) {
                        launchSingleTop = true
                    }
                },
                onQueueClick = {
                    navController.navigate(WearScreens.QUEUE) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(WearScreens.VOLUME) {
            VolumeScreen()
        }

        composable(WearScreens.OUTPUT) {
            OutputScreen()
        }

        composable(WearScreens.MORE) {
            MoreScreen(
                onQueueClick = {
                    navController.navigate(WearScreens.QUEUE) {
                        launchSingleTop = true
                    }
                },
                onSettingsClick = {
                    navController.navigate(WearScreens.OUTPUT) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(WearScreens.QUEUE) {
            QueueScreen(
                onTimerClick = {
                    navController.navigate(WearScreens.TIMER) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(WearScreens.TIMER) {
            TimerScreen()
        }

        composable(WearScreens.DOWNLOADS) {
            DownloadsScreen(
                onPlaylistsClick = {
                    navController.navigate(WearScreens.LOCAL_PLAYLISTS) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(WearScreens.LOCAL_PLAYLISTS) {
            LocalPlaylistsScreen(
                onPlaylistClick = { playlistId, name ->
                    navController.navigate(WearScreens.localPlaylistDetailRoute(playlistId, name))
                },
            )
        }

        composable(
            route = WearScreens.LOCAL_PLAYLIST_DETAIL,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getString("playlistId") ?: ""
            val name = URLDecoder.decode(
                backStackEntry.arguments?.getString("name") ?: "", "UTF-8"
            )
            LocalPlaylistDetailScreen(
                playlistId = playlistId,
                playlistName = name,
            )
        }

        composable(WearScreens.BROWSE) {
            BrowseScreen(
                onCategoryClick = navigateToBrowseCategory,
            )
        }

        composable(
            route = WearScreens.LIBRARY_LIST,
            arguments = listOf(
                navArgument("browseType") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val browseType = backStackEntry.arguments?.getString("browseType") ?: ""
            val title = URLDecoder.decode(
                backStackEntry.arguments?.getString("title") ?: "", "UTF-8"
            )
            LibraryListScreen(
                browseType = browseType,
                title = title,
                onItemClick = { item, subBrowseType, itemTitle ->
                    navController.navigate(
                        WearScreens.songListRoute(subBrowseType, item.id, itemTitle)
                    )
                },
            )
        }

        composable(
            route = WearScreens.SONG_LIST,
            arguments = listOf(
                navArgument("browseType") { type = NavType.StringType },
                navArgument("contextId") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val browseType = backStackEntry.arguments?.getString("browseType") ?: ""
            val contextId = backStackEntry.arguments?.getString("contextId")
            val title = URLDecoder.decode(
                backStackEntry.arguments?.getString("title") ?: "", "UTF-8"
            )
            SongListScreen(
                browseType = browseType,
                contextId = contextId,
                title = title,
            )
        }
    }
}
