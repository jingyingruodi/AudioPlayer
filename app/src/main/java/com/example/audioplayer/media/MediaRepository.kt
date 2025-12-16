package com.example.audioplayer.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.audioplayer.Music

object MediaRepository {
    suspend fun loadAudioFiles(context: Context): List<Music> {
        val songs = mutableListOf<Music>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC}=1"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val queryUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        context.contentResolver.query(queryUri, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown"
                val artist = cursor.getString(artistCol) ?: ""
                val album = cursor.getString(albumCol) ?: ""
                val duration = cursor.getLong(durationCol)

                val contentUri: Uri = ContentUris.withAppendedId(queryUri, id)
                songs.add(Music(id, title, artist, album, duration, contentUri, null))
            }
        }

        return songs
    }

    // Load metadata for a set of document URIs (e.g., from Storage Access Framework)
    suspend fun loadFromUris(context: Context, uris: List<Uri>): List<Music> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Music>()
        val mmr = MediaMetadataRetriever()
        try {
            for (u in uris) {
                try {
                    // take persistable permission if available
                    context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                try {
                    mmr.setDataSource(context, u)
                    val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: (u.lastPathSegment ?: "Unknown")
                    val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                    val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                    val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val duration = dur?.toLongOrNull() ?: 0L
                    result.add(Music(u.hashCode().toLong(), title, artist, album, duration, u, null))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
        result
    }
}
