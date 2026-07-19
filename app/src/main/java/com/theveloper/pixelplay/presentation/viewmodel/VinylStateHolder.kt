package com.theveloper.pixelplay.presentation.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VinylStateHolder @Inject constructor() {
    private val _showVinylPlayer = MutableStateFlow(false)
    val showVinylPlayer: StateFlow<Boolean> = _showVinylPlayer.asStateFlow()

    fun toggleVinylPlayer() {
        _showVinylPlayer.value = !_showVinylPlayer.value
    }

    fun setShowVinylPlayer(show: Boolean) {
        _showVinylPlayer.value = show
    }
}
