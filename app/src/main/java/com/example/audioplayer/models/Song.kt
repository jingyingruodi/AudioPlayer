package com.example.audioplayer.models

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val duration: Long,
    val uri: Uri
)

