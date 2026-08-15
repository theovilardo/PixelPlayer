package com.theveloper.pixelplay.data.coverart

/**
 * Where an applied cover is kept.
 *
 * The two options differ in what survives the app: art written into the audio
 * files travels with them to any other player or machine, while art kept in the
 * app leaves the user's files untouched and disappears with the app's data.
 */
enum class AlbumArtStorage {
    /**
     * Embedded into every track of the album, the way a tag editor would.
     *
     * Modifying files the app did not create needs the user's consent per file
     * on Android 11 and up, which can only be asked for on screen. Covers found
     * by the unattended pass are therefore still kept in the app, whatever this
     * is set to.
     */
    AUDIO_FILES,

    /**
     * Kept in the app's own artwork store, leaving the audio files untouched.
     *
     * No write consent and no tag rewrite, at the cost of art no other player
     * sees. The default: the unattended pass can only ever write here, and one
     * store keeps "where is this album's cover" answerable.
     */
    APP_ONLY
}
