package com.example.audioplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MusicService : Service() {

    private val binder = MusicBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var musicList: List<Music> = emptyList()
    private var currentPosition = -1

    companion object {
        const val CHANNEL_ID = "MusicServiceChannel"
        const val ACTION_PLAY = "com.example.audioplayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.audioplayer.ACTION_PAUSE"
        const val ACTION_NEXT = "com.example.audioplayer.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.audioplayer.ACTION_PREVIOUS"
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Accept playlist sent from Activity
        intent?.getParcelableArrayListExtra<Music>("playlist")?.let { list ->
            if (list.isNotEmpty()) {
                musicList = list
            }
        }

        // Handle explicit play requests with a uri extra
        intent?.let {
            when (it.action) {
                ACTION_PLAY -> {
                    val uriString = it.getStringExtra("song_uri")
                    val pos = it.getIntExtra("song_pos", -1)
                    if (uriString != null) {
                        try {
                            val uri = Uri.parse(uriString)
                            mediaPlayer?.release()
                            mediaPlayer = MediaPlayer().apply {
                                setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .build()
                                )
                                setDataSource(applicationContext, uri)
                                prepare()
                                start()
                                setOnCompletionListener { playNext() }
                            }

                            val titleGuess = uri.lastPathSegment ?: "Unknown"
                            val tmpMusic = Music(uriString.hashCode().toLong(), titleGuess, "", "", mediaPlayer?.duration?.toLong() ?: 0L, uri, null)

                            // Update notification only (no media session)
                            updateNotification(tmpMusic, true)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else if (pos >= 0) {
                        playMusic(pos)
                    }
                }
                ACTION_PAUSE -> pause()
                ACTION_NEXT -> playNext()
                ACTION_PREVIOUS -> playPrevious()
            }
        }
        return START_NOT_STICKY
    }

    fun setPlaylist(list: List<Music>) {
        musicList = list
    }

    fun playMusic(position: Int) {
        if (position < 0 || position >= musicList.size) return
        currentPosition = position
        val music = musicList[position]

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(applicationContext, music.uri)
            prepare()
            start()
            setOnCompletionListener { playNext() }
        }

        updateNotification(music, true)
        // no media session update
    }

    fun play() {
        if (mediaPlayer != null && !mediaPlayer!!.isPlaying) {
            mediaPlayer?.start()
            if (currentPosition != -1) {
                updateNotification(musicList[currentPosition], true)
            }
        }
    }

    fun pause() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            if (currentPosition != -1) {
                updateNotification(musicList[currentPosition], false)
            }
        }
    }

    fun playNext() {
        if (musicList.isEmpty()) return
        var nextPos = currentPosition + 1
        if (nextPos >= musicList.size) nextPos = 0
        playMusic(nextPos)
    }

    fun playPrevious() {
        if (musicList.isEmpty()) return
        var prevPos = currentPosition - 1
        if (prevPos < 0) prevPos = musicList.size - 1
        playMusic(prevPos)
    }

    fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying == true
    }

    fun getCurrentMusic(): Music? {
        if (currentPosition != -1 && currentPosition < musicList.size) {
            return musicList[currentPosition]
        }
        return null
    }

    private fun updateNotification(music: Music, isPlaying: Boolean) {
        val playIntent = Intent(this, MusicService::class.java).apply { action = ACTION_PLAY }
        val pauseIntent = Intent(this, MusicService::class.java).apply { action = ACTION_PAUSE }
        val nextIntent = Intent(this, MusicService::class.java).apply { action = ACTION_NEXT }
        val prevIntent = Intent(this, MusicService::class.java).apply { action = ACTION_PREVIOUS }
        val activityIntent = Intent(this, MainActivity::class.java)

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_pause,
                "Pause",
                PendingIntent.getService(this, 1, pauseIntent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play,
                "Play",
                PendingIntent.getService(this, 0, playIntent, PendingIntent.FLAG_IMMUTABLE)
            )
        }

        val largeIcon: Bitmap? = null

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(music.title)
            .setContentText(music.artist)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    activityIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_previous,
                    "Prev",
                    PendingIntent.getService(this, 2, prevIntent, PendingIntent.FLAG_IMMUTABLE)
                )
            )
            .addAction(playPauseAction)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_next,
                    "Next",
                    PendingIntent.getService(this, 3, nextIntent, PendingIntent.FLAG_IMMUTABLE)
                )
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(music.artist)
            )
            .setOngoing(isPlaying)

        if (largeIcon != null) {
            notificationBuilder.setLargeIcon(largeIcon)
        }

        startForeground(1, notificationBuilder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Music Player Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}
