package com.theveloper.pixelplay.shared

/**
 * Shared constants for Wear Data Layer API paths.
 * Used by both the phone app and the Wear OS app for communication.
 */
object WearDataPaths {
    /** DataItem path for player state (phone -> watch) */
    const val PLAYER_STATE = "/player_state"

    /** Message path for playback commands (watch -> phone) */
    const val PLAYBACK_COMMAND = "/playback_command"

    /** Message path for playback command results (phone -> watch) */
    const val PLAYBACK_RESULT = "/playback_result"

    /** Message path for volume commands (watch -> phone) */
    const val VOLUME_COMMAND = "/volume_command"

    /** Message path for volume state updates (phone -> watch) */
    const val VOLUME_STATE = "/volume_state"

    /** Key for the album art Asset within a DataItem */
    const val KEY_ALBUM_ART = "album_art"

    /** Key for the JSON state payload within a DataItem */
    const val KEY_STATE_JSON = "state_json"

    /** Key for timestamp to force DataItem updates */
    const val KEY_TIMESTAMP = "timestamp"

    /** Message path for library browse requests (watch -> phone) */
    const val BROWSE_REQUEST = "/browse_request"

    /** Message path for library browse responses (phone -> watch) */
    const val BROWSE_RESPONSE = "/browse_response"

    /** Message path for watch library status queries */
    const val WATCH_LIBRARY_QUERY = "/watch_library_query"

    /** Message path for watch library state responses */
    const val WATCH_LIBRARY_STATE = "/watch_library_state"

    /** Message path for transfer requests (watch -> phone) */
    const val TRANSFER_REQUEST = "/transfer_request"

    /** Message path for transfer metadata (phone -> watch, sent before channel stream) */
    const val TRANSFER_METADATA = "/transfer_metadata"

    /** ChannelClient path for audio file streaming (phone -> watch) */
    const val TRANSFER_CHANNEL = "/transfer_audio"

    /** ChannelClient path for artwork streaming (phone -> watch) */
    const val TRANSFER_ARTWORK_CHANNEL = "/transfer_artwork"

    /** Message path for transfer progress updates (phone -> watch) */
    const val TRANSFER_PROGRESS = "/transfer_progress"

    /** Message path for transfer cancellation (watch -> phone) */
    const val TRANSFER_CANCEL = "/transfer_cancel"

    /** Message path for favorites sync requests (watch -> phone) */
    const val FAVORITES_SYNC_REQUEST = "/favorites_sync_request"

    /** Message path for favorites sync progress/state (phone -> watch) */
    const val FAVORITES_SYNC_STATE = "/favorites_sync_state"

    /** Message path for playlist sync (phone -> watch): creates or updates a local playlist's membership/order. */
    const val PLAYLIST_SYNC = "/playlist_sync"

    /**
     * Message path for playlist sync acknowledgement (watch -> phone): confirms a [PLAYLIST_SYNC]
     * message was actually applied, since `MessageClient.sendMessage()` succeeding on the phone
     * only means local hand-off, not delivery.
     */
    const val PLAYLIST_SYNC_ACK = "/playlist_sync_ack"

    /**
     * DataItem path for the watch performance toggles configured from the phone's Settings ->
     * "Reloj" screen (phone -> watch). DataItem, not MessageClient: these need to durably reach
     * the watch even if it's mid-reconnect when the phone publishes, same reasoning as
     * [PLAYER_STATE] — see [PLAYLIST_SYNC]'s ack for what happens when a phone->watch send is
     * only best-effort instead.
     */
    const val WEAR_PERFORMANCE_SETTINGS = "/wear_performance_settings"

    /** DataMap keys within [WEAR_PERFORMANCE_SETTINGS]. All three default to `true` on the watch
     *  if never synced, preserving today's behavior. */
    const val KEY_SHOW_ALBUM_ART = "show_album_art"
    const val KEY_DYNAMIC_COLOR_THEMING = "dynamic_color_theming"
    const val KEY_PLAY_BUTTON_ANIMATION = "play_button_animation"
}
