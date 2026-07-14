@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TabRowDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.theveloper.pixelplay.R
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.theveloper.pixelplay.presentation.screens.TabAnimation
import com.theveloper.pixelplay.presentation.viewmodel.DirectoryEntry
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.LocalShowScrollbar
import com.theveloper.pixelplay.utils.StorageInfo
import java.io.File

enum class FileExplorerTab {
    COLLAPSED, EXPANDED
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileExplorerDialog(
    visible: Boolean,
    currentPath: File,
    directoryChildren: List<DirectoryEntry>,
    flatDirectoryChildren: List<DirectoryEntry>,
    availableStorages: List<StorageInfo>,
    selectedStorageIndex: Int,
    isLoading: Boolean,
    isPriming: Boolean = false,
    isReady: Boolean = true,
    isCurrentDirectoryResolved: Boolean = true,
    isAtRoot: Boolean,
    rootDirectory: File,
    onNavigateTo: (File) -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateHome: () -> Unit,
    onToggleAllowed: (File) -> Unit,
    onRefresh: () -> Unit,
    onStorageSelected: (Int) -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit
) {
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = visible

    if (transitionState.currentState || transitionState.targetState) {
        Dialog(
            onDismissRequest = {
                if (!isAtRoot) {
                    onNavigateUp()
                } else {
                    onDismiss()
                }
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            AnimatedVisibility(
                visibleState = transitionState,
                enter = slideInVertically(initialOffsetY = { it / 6 }) + fadeIn(animationSpec = tween(220)),
                exit = slideOutVertically(targetOffsetY = { it / 6 }) + fadeOut(animationSpec = tween(200)),
                label = "file_explorer_dialog"
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    FileExplorerContent(
                        currentPath = currentPath,
                        directoryChildren = directoryChildren,
                        flatDirectoryChildren = flatDirectoryChildren,
                        availableStorages = availableStorages,
                        selectedStorageIndex = selectedStorageIndex,
                        isLoading = isLoading,
                        isPriming = isPriming,
                        isReady = isReady,
                        isCurrentDirectoryResolved = isCurrentDirectoryResolved,
                        isAtRoot = isAtRoot,
                        rootDirectory = rootDirectory,
                        onNavigateTo = onNavigateTo,
                        onNavigateUp = onNavigateUp,
                        onNavigateHome = onNavigateHome,
                        onToggleAllowed = onToggleAllowed,
                        onRefresh = onRefresh,
                        onStorageSelected = onStorageSelected,
                        onDone = onDone,
                        onDismiss = onDismiss,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerContent(
    currentPath: File,
    directoryChildren: List<DirectoryEntry>,
    flatDirectoryChildren: List<DirectoryEntry>,
    availableStorages: List<StorageInfo>,
    selectedStorageIndex: Int,
    isLoading: Boolean,
    isPriming: Boolean,
    isReady: Boolean,
    isCurrentDirectoryResolved: Boolean,
    isAtRoot: Boolean,
    rootDirectory: File,
    onNavigateTo: (File) -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateHome: () -> Unit,
    onToggleAllowed: (File) -> Unit,
    onRefresh: () -> Unit,
    onStorageSelected: (Int) -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "Excluded folders",
    leadingContent: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val safeSelectedStorageIndex = remember(availableStorages, selectedStorageIndex) {
        if (availableStorages.isEmpty()) {
            0
        } else {
            selectedStorageIndex.coerceIn(0, availableStorages.lastIndex)
        }
    }
    val showLoadingState = remember(
        directoryChildren,
        isLoading,
        isPriming,
        isReady,
        isCurrentDirectoryResolved
    ) {
        directoryChildren.isEmpty() && (
            isLoading || isPriming || !isReady || !isCurrentDirectoryResolved
        )
    }
    var currentTab by remember { mutableStateOf(FileExplorerTab.COLLAPSED) }
    val showExpandedLoadingState = remember(
        flatDirectoryChildren,
        isLoading,
        isPriming,
        isReady
    ) {
        flatDirectoryChildren.isEmpty() && (
            isLoading || isPriming || !isReady
        )
    }
    val loadingMessage = remember(isPriming, isReady) {
        if (isPriming || !isReady) {
            "Preparing folders…"
        } else {
            "Loading folders…"
        }
    }
    val loadingHint = remember(isPriming, isReady) {
        if (isPriming || !isReady) {
            "This can take a moment while PixelPlay scans the available subfolders."
        } else {
            null
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = title,
                        fontFamily = GoogleSansRounded,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 22.sp,
//                            textGeometricTransform = TextGeometricTransform(scaleX = 1.2f),
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                navigationIcon = {
                    FilledIconButton(
                        modifier = Modifier.padding(start = 6.dp),
                        onClick = onDismiss,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.common_close)
                        )
                    }
                },
                actions = {
                    FilledIconButton(
                        modifier = Modifier.padding(end = 6.dp),
                        onClick = onRefresh,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.file_explorer_cd_refresh)
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets.systemBars,
        floatingActionButton = {
            MediumExtendedFloatingActionButton(
                modifier = Modifier.padding(end = 12.dp),
                onClick = onDone,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Done,
                    contentDescription = stringResource(R.string.common_done),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = stringResource(R.string.common_done))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(7.2.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.file_explorer_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 18.dp)
            )

            // Only show storage tabs if there's more than one storage
            if (availableStorages.size > 1) {
                PrimaryTabRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(5.dp),
                    selectedTabIndex = safeSelectedStorageIndex,
                    containerColor = Color.Transparent,
                    indicator = {
                        TabRowDefaults.PrimaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(
                                selectedTabIndex = safeSelectedStorageIndex,
                                matchContentSize = true
                            ),
                            height = 3.dp,
                            color = Color.Transparent
                        )
                    },
                    divider = {}
                ) {
                    availableStorages.forEachIndexed { index, storage ->
                        TabAnimation(
                            index = index,
                            title = storage.displayName,
                            selectedIndex = safeSelectedStorageIndex,
                            onClick = { onStorageSelected(index) }
                        ) {
                            Text(
                                text = storage.displayName,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                fontFamily = GoogleSansRounded
                            )
                        }
                    }
                }
            }
            PrimaryTabRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(5.dp),
                selectedTabIndex = currentTab.ordinal,
                containerColor = Color.Transparent,
                indicator = {
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(
                            selectedTabIndex = currentTab.ordinal,
                            matchContentSize = true
                        ),
                        height = 3.dp,
                        color = Color.Transparent
                    )
                },
                divider = {}
            ) {
                TabAnimation(
                    index = 0,
                    title = "Folders",
                    selectedIndex = currentTab.ordinal,
                    onClick = { currentTab = FileExplorerTab.COLLAPSED }
                ) {
                    Text(
                        text = "Folders",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        fontFamily = GoogleSansRounded
                    )
                }
                TabAnimation(
                    index = 1,
                    title = "All subfolders",
                    selectedIndex = currentTab.ordinal,
                    onClick = { currentTab = FileExplorerTab.EXPANDED }
                ) {
                    Text(
                        text = "All subfolders",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        fontFamily = GoogleSansRounded
                    )
                }
            }

            FileExplorerHeader(
                modifier = Modifier.padding(horizontal = 18.dp),
                currentPath = if (currentTab == FileExplorerTab.COLLAPSED) currentPath else rootDirectory,
                rootDirectory = rootDirectory,
                isAtRoot = if (currentTab == FileExplorerTab.COLLAPSED) isAtRoot else true,
                onNavigateUp = onNavigateUp,
                onNavigateHome = onNavigateHome,
                onNavigateTo = onNavigateTo,
                navigationEnabled = currentTab == FileExplorerTab.COLLAPSED
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 18.dp)
            ) {
                AnimatedContent(
                    targetState = Triple(
                        currentTab,
                        if (currentTab == FileExplorerTab.COLLAPSED) directoryChildren else flatDirectoryChildren,
                        if (currentTab == FileExplorerTab.COLLAPSED) currentPath else null
                    ),
                    label = "directory_content",
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(200))
                    }
                ) { (tab, children, _) ->
                    val showLoading = if (tab == FileExplorerTab.COLLAPSED) {
                        showLoadingState
                    } else {
                        showExpandedLoadingState
                    }

                    when {
                        showLoading -> ExplorerLoadingState(
                            message = loadingMessage,
                            supportingText = loadingHint
                        )

                        children.isEmpty() -> ExplorerEmptyState(text = stringResource(R.string.file_explorer_empty_folders))

                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // Padding horizontal moved to parent Box
                                    .clip(
                                        RoundedCornerShape(
                                            topEnd = 20.dp,
                                            topStart = 20.dp
                                        )
                                    ),
                                contentPadding = PaddingValues(
                                    bottom = 24.dp,
                                    end = if (LocalShowScrollbar.current && (listState.canScrollForward || listState.canScrollBackward)) 24.dp else 0.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                state = listState
                            ) {
                                items(children, key = { it.file.absolutePath }) { directoryEntry ->
                                    val displayCount = directoryEntry.totalAudioCount

                                    FileExplorerItem(
                                        file = directoryEntry.file,
                                        audioCount = displayCount,
                                        displayName = directoryEntry.displayName,
                                        isBlocked = directoryEntry.isBlocked,
                                        onNavigate = { onNavigateTo(directoryEntry.file) },
                                        onToggleAllowed = { onToggleAllowed(directoryEntry.file) },
                                        navigationEnabled = tab == FileExplorerTab.COLLAPSED
                                    )
                                }
                                item { Spacer(modifier = Modifier.height(76.dp)) }
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(30.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                )
                            )
                        )
                )
                
                ExpressiveScrollBar(
                    listState = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            top = 6.dp, 
                            bottom = 88.dp // Match bottom content padding roughly (24) + FAB height (56) + spacing (8)
                        )
                )
            }
        }
    }
}

@Composable
private fun ExplorerEmptyState(
    text: String,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.FolderOff,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ExplorerLoadingState(
    message: String,
    supportingText: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ContainedLoadingIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (supportingText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FileExplorerItem(
    file: File,
    audioCount: Int,
    displayName: String?,
    isBlocked: Boolean,
    onNavigate: () -> Unit,
    onToggleAllowed: () -> Unit,
    navigationEnabled: Boolean
) {
    val shape = RoundedCornerShape(18.dp)

    val isAllowed = !isBlocked

    val containerColor = if (isBlocked) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val contentColor = if (isBlocked) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val badgeColor = if (isBlocked) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.secondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor)
            .clickable {
                if (navigationEnabled) {
                    onNavigate()
                } else {
                    onToggleAllowed()
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(shape)
                .background(containerColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = contentColor
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName ?: file.name.ifEmpty { file.path },
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = file.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis
            )
            Spacer(
                modifier = Modifier
                    .height(6.dp)
                    .fillMaxWidth()
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(badgeColor.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = when {
                        audioCount < 0 -> "Scanning..."
                        audioCount == 1 -> "1 song"
                        audioCount > 99 -> "99+ songs"
                        else -> "$audioCount songs"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = badgeColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (navigationEnabled) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.width(8.dp))
        }

        Checkbox(
            checked = isBlocked,
            onCheckedChange = { onToggleAllowed() },
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.onErrorContainer,
                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkmarkColor = MaterialTheme.colorScheme.errorContainer
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileExplorerHeader(
    modifier: Modifier,
    currentPath: File,
    rootDirectory: File,
    isAtRoot: Boolean,
    onNavigateUp: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateTo: (File) -> Unit,
    navigationEnabled: Boolean
) {
    // 1. Cambiamos ScrollState por LazyListState para manejar mejor los ítems y el scroll automático
    val listState = rememberLazyListState()

    val breadcrumbs by remember(currentPath, rootDirectory) {
        mutableStateOf(buildList {
            var cursor: File? = currentPath
            val rootPath = rootDirectory.path
            while (cursor != null) {
                add(cursor)
                if (cursor.path == rootPath) break
                cursor = cursor.parentFile
            }
            reverse()
        })
    }

    val rootLabel = remember(rootDirectory) {
        when (rootDirectory.name) {
            "0", "" -> "Internal storage"
            else -> rootDirectory.name
        }
    }

    // 2. Lógica para detectar si hay contenido oculto a los lados
    val showStartFade by remember { derivedStateOf { listState.canScrollBackward } }
    val showEndFade by remember { derivedStateOf { listState.canScrollForward } }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Botón de "Atrás" (Back Arrow) - Se mantiene igual fuera del scroll
            if (!isAtRoot && navigationEnabled) {
                IconButton(
                    onClick = onNavigateUp,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.file_explorer_cd_navigate_up),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (!isAtRoot) {
                // 3. Auto-scroll al final cuando cambia el path
                LaunchedEffect(breadcrumbs.size) {
                    if (breadcrumbs.isNotEmpty()) {
                        listState.animateScrollToItem(breadcrumbs.lastIndex)
                    }
                }

                // 4. Reemplazamos el Row + horizontalScroll por LazyRow con el efecto gráfico
                LazyRow(
                    state = listState,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        // APLICACIÓN DEL EFECTO DE GRADIENTE
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val gradientWidth = 24.dp.toPx()

                            // Fade Izquierdo
                            if (showStartFade) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(Color.Transparent, Color.Black),
                                        endX = gradientWidth
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }

                            // Fade Derecho
                            if (showEndFade) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(Color.Black, Color.Transparent),
                                        startX = size.width - gradientWidth
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                        }
                ) {
                    // Spacer inicial para que el primer ítem no quede pegado al borde o debajo del fade
                    item { Spacer(modifier = Modifier.width(4.dp)) }

                    items(breadcrumbs.size, key = { breadcrumbs[it].path }) { index ->
                        val file = breadcrumbs[index]
                        val isRoot = file.path == rootDirectory.path
                        val isLast = index == breadcrumbs.lastIndex
                        val label = if (isRoot) rootLabel else file.name.ifEmpty { file.path }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Diseño del Chip (Mantenemos tu estilo visual original)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable(enabled = !isLast && navigationEnabled) {
                                        if (isRoot) onNavigateHome() else onNavigateTo(file)
                                    }
                                    .background(
                                        color = if (isLast) {
                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        shape = CircleShape
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                if (isRoot) {
                                    Icon(
                                        imageVector = Icons.Rounded.Home,
                                        contentDescription = stringResource(R.string.file_explorer_cd_go_root),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .size(16.dp)
                                    )
                                }
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isLast) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Separador (Chevron)
                            if (!isLast) {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 6.dp, end = 2.dp)
                                )
                            }
                        }
                    }

                    // Spacer final para dar aire al último elemento
                    item { Spacer(modifier = Modifier.width(12.dp)) }
                }
            }
        }
    }
}
