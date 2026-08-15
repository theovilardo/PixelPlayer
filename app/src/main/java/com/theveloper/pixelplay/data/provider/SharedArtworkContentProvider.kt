package com.theveloper.pixelplay.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.theveloper.pixelplay.utils.AlbumArtUtils
import java.io.File
import java.io.FileNotFoundException

class SharedArtworkContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? {
        val appContext = context?.applicationContext ?: return null
        val songId = parseSongId(uri, appContext.packageName) ?: return null
        // Applied covers are WebP, extracted art is JPEG, and declaring the
        // wrong one leaves anything trusting the declared type -- a share
        // target, Android Auto -- with an image it cannot decode. Only the
        // applied store is consulted: it wins when both exist and costs a
        // pointer lookup, while resolving the cache could run a MediaStore
        // query and a decode on the caller's main thread.
        return if (AlbumArtUtils.getAppliedAlbumArtFile(appContext, songId) != null) {
            WEBP_CONTENT_TYPE
        } else {
            DEFAULT_CONTENT_TYPE
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Shared artwork provider is read-only")
        }

        val file = resolveArtworkFile(uri)
            ?: throw FileNotFoundException("No artwork found for uri=$uri")

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val fileDescriptor = openFile(uri, mode)
        return AssetFileDescriptor(fileDescriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun resolveArtworkFile(uri: Uri): File? {
        val appContext = context?.applicationContext ?: return null
        val songId = parseSongId(uri, appContext.packageName) ?: return null
        return AlbumArtUtils.ensureAlbumArtCachedFile(
            appContext = appContext,
            songId = songId
        )?.takeIf { it.exists() && it.isFile && it.canRead() }
    }

    companion object {
        private const val AUTHORITY_SUFFIX = ".artwork"
        private const val PATH_SONG = "song"
        private const val DEFAULT_CONTENT_TYPE = "image/jpeg"
        private const val WEBP_CONTENT_TYPE = "image/webp"

        fun authority(packageName: String): String = packageName + AUTHORITY_SUFFIX

        fun buildSongUri(
            context: Context,
            songId: Long,
            cacheBustToken: String? = null
        ): Uri = buildSongUri(context.packageName, songId, cacheBustToken)

        internal fun buildSongUri(
            packageName: String,
            songId: Long,
            cacheBustToken: String? = null
        ): Uri {
            return Uri.parse(buildSongUriString(packageName, songId, cacheBustToken))
        }

        internal fun buildSongUriString(
            packageName: String,
            songId: Long,
            cacheBustToken: String? = null
        ): String {
            val baseUri = "content://${authority(packageName)}/$PATH_SONG/$songId"
            return cacheBustToken
                ?.takeIf { it.isNotBlank() }
                ?.let { "$baseUri?t=$it" }
                ?: baseUri
        }

        internal fun parseSongId(uri: Uri, packageName: String? = null): Long? {
            return parseSongId(uri.toString(), packageName)
        }

        internal fun parseSongId(uriString: String, packageName: String? = null): Long? {
            val expectedPrefix = packageName
                ?.let(::authority)
                ?.let { "content://$it/$PATH_SONG/" }

            if (expectedPrefix != null && !uriString.startsWith(expectedPrefix)) {
                return null
            }

            val basePrefix = expectedPrefix ?: run {
                val authoritySeparator = "://"
                val schemeSplit = uriString.indexOf(authoritySeparator)
                if (schemeSplit < 0) return null
                val pathStart = uriString.indexOf('/', schemeSplit + authoritySeparator.length)
                if (pathStart < 0) return null
                val pathPrefix = uriString.substring(pathStart)
                if (!pathPrefix.startsWith("/$PATH_SONG/")) return null
                uriString.substring(0, pathStart) + "/$PATH_SONG/"
            }

            val songIdSegment = uriString
                .removePrefix(basePrefix)
                .substringBefore('?')
                .substringBefore('/')

            if (songIdSegment.isBlank()) {
                return null
            }
            return songIdSegment.toLongOrNull()
        }
    }
}
