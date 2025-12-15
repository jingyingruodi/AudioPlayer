package com.example.audioplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvNowPlaying: TextView
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton

    private var musicService: MusicService? = null
    private var isBound = false
    private var musicList: List<Music> = emptyList()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            musicService?.setPlaylist(musicList)
            updateUI()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        tvNowPlaying = findViewById(R.id.tvNowPlaying)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)

        recyclerView.layoutManager = LinearLayoutManager(this)

        checkPermissions()
        setupControls()
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(neededPermissions.toTypedArray())
        } else {
            loadMusic()
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                loadMusic()
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private fun loadMusic() {
        musicList = MusicScanner.getAllAudio(this)
        val adapter = MusicAdapter(musicList) { position ->
            musicService?.playMusic(position)
            updateUI()
        }
        recyclerView.adapter = adapter

        // Start and Bind Service
        val intent = Intent(this, MusicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun setupControls() {
        btnPlayPause.setOnClickListener {
            if (musicService?.isPlaying() == true) {
                musicService?.pause()
            } else {
                musicService?.play()
            }
            updateUI()
        }

        btnNext.setOnClickListener {
            musicService?.playNext()
            updateUI()
        }

        btnPrevious.setOnClickListener {
            musicService?.playPrevious()
            updateUI()
        }
    }

    private fun updateUI() {
        if (musicService?.isPlaying() == true) {
            btnPlayPause.setImageResource(R.drawable.ic_pause)
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_play)
        }

        val currentMusic = musicService?.getCurrentMusic()
        if (currentMusic != null) {
            tvNowPlaying.text = "${currentMusic.title} - ${currentMusic.artist}"
        } else {
            tvNowPlaying.text = "Not Playing"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (isBound) {
            updateUI()
        }
    }
}
