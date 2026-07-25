package com.theveloper.pixelplay.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SortOptionTest {

    @Test
    fun `method option keeps a single representative per sort method`() {
        assertEquals(SortOption.SongTitleAZ, SortOption.SongTitleZA.methodOption())
        assertEquals(SortOption.SongArtist, SortOption.SongArtistDesc.methodOption())
        assertEquals(SortOption.SongDateAdded, SortOption.SongDateAddedAsc.methodOption())
        assertEquals(SortOption.SongListeningTime, SortOption.SongListeningTimeAsc.methodOption())
        assertEquals(SortOption.SongDefaultOrder, SortOption.SongDefaultOrder.methodOption())
    }

    @Test
    fun `resolve for direction keeps method while switching order`() {
        assertEquals(
            SortOption.SongArtistDesc,
            SortOption.SongArtist.resolveForDirection(SortDirection.Descending)
        )
        assertEquals(
            SortOption.SongArtist,
            SortOption.SongArtistDesc.resolveForDirection(SortDirection.Ascending)
        )
        assertEquals(
            SortOption.SongDateAddedAsc,
            SortOption.SongDateAdded.resolveForDirection(SortDirection.Ascending)
        )
        assertEquals(
            SortOption.PlaylistDateCreated,
            SortOption.PlaylistDateCreatedAsc.resolveForDirection(SortDirection.Descending)
        )
    }

    @Test
    fun `flip direction swaps paired sort options`() {
        assertEquals(SortOption.SongTitleZA, SortOption.SongTitleAZ.flipDirection())
        assertEquals(SortOption.SongTitleAZ, SortOption.SongTitleZA.flipDirection())
        assertEquals(SortOption.LikedSongDateLikedAsc, SortOption.LikedSongDateLiked.flipDirection())
        assertEquals(SortOption.FolderSongCountAsc, SortOption.FolderSongCountDesc.flipDirection())
        assertEquals(SortOption.SongListeningTimeAsc, SortOption.SongListeningTime.flipDirection())
        assertEquals(SortOption.SongListeningTime, SortOption.SongListeningTimeAsc.flipDirection())
        assertEquals(SortOption.SongDefaultOrder, SortOption.SongDefaultOrder.flipDirection())
    }

    @Test
    fun `from storage key still resolves legacy display names`() {
        val resolved = SortOption.fromStorageKey(
            rawValue = SortOption.SongArtist.displayName,
            allowed = SortOption.SONGS,
            fallback = SortOption.SongTitleAZ
        )

        assertEquals(SortOption.SongArtist, resolved)
    }
}
