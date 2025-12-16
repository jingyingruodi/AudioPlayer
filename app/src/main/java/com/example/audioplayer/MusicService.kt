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
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

class MusicService : Service() {

    private val binder = MusicBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var musicList: List<Music> = emptyList()
    private var currentPosition = -1
    private lateinit var mediaSession: MediaSessionCompat

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
        initMediaSession()
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicService").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    play()
                }

                override fun onPause() {
                    pause()
                }

                override fun onSkipToNext() {
                    playNext()
                }

                override fun onSkipToPrevious() {
                    playPrevious()
                }
            })
            isActive = true
        }
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

                            // build a minimal Music object (Music requires non-null artist/album)
                            val titleGuess = uri.lastPathSegment ?: "Unknown"
                            val tmpMusic = Music(uriString.hashCode().toLong(), titleGuess, "", "", mediaPlayer?.duration?.toLong() ?: 0L, uri, null)

                            // Update session metadata with minimal info
                            updateMediaSession(tmpMusic, PlaybackStateCompat.STATE_PLAYING)
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
        updateMediaSession(music, PlaybackStateCompat.STATE_PLAYING)
    }

    fun play() {
        if (mediaPlayer != null && !mediaPlayer!!.isPlaying) {
            mediaPlayer?.start()
            if (currentPosition != -1) {
                updateNotification(musicList[currentPosition], true)
                updateMediaSession(musicList[currentPosition], PlaybackStateCompat.STATE_PLAYING)
            }
        }
    }

    fun pause() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            if (currentPosition != -1) {
                updateNotification(musicList[currentPosition], false)
                updateMediaSession(musicList[currentPosition], PlaybackStateCompat.STATE_PAUSED)
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

    private fun updateMediaSession(music: Music, state: Int) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, music.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, music.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, music.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, music.duration)
                .build()
        )

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, mediaPlayer?.currentPosition?.toLong() ?: 0L, 1.0f)
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .build()
        )
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

        // Use a null Bitmap variable to avoid ambiguity if needed, or just don't set it if null
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
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
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
        mediaSession.release()
    }
}
