package com.example.audioplayer

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.audioplayer.media.MediaRepository
import com.example.audioplayer.ui.MusicListAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val adapter by lazy { MusicListAdapter { song, pos -> onSongClicked(song, pos) } }
    // use an explicit Job so we can cancel it cleanly in onDestroy
    private val mainJob = Job()
    private val scope = CoroutineScope(mainJob + Dispatchers.Main)

    // Service binding
    private var musicService: MusicService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MusicService.MusicBinder
            musicService = binder?.getService()
            bound = true
            // if we already loaded items, sync playlist to service
            val currentItems = adapterItems()
            if (currentItems.isNotEmpty()) {
                musicService?.setPlaylist(currentItems)
            }
            refreshNowPlaying()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            musicService = null
            refreshNowPlaying()
        }
    }

    private fun adapterItems(): List<Music> {
        // reflection-like access: MusicListAdapter doesn't expose items, so we keep a copy via adapter.setItems usage.
        // To keep changes minimal, we'll store the last loaded list in a field.
        return lastLoadedList
    }

    private var lastLoadedList: List<Music> = emptyList()

    private fun refreshNowPlaying() {
        val tv = findViewById<android.widget.TextView>(R.id.tvNowPlaying)
        val btn = findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
        val current = musicService?.getCurrentMusic()
        if (current != null) {
            tv.text = getString(R.string.now_playing, current.title, current.artist)
            btn.setImageResource(if (musicService?.isPlaying() == true) R.drawable.ic_pause else R.drawable.ic_play)
        } else {
            tv.text = "未播放"
            btn.setImageResource(R.drawable.ic_play)
        }
    }

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        val grantedAll = perms.values.all { it }
        if (grantedAll) loadSongs()
        else showPermissionRationale()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerSongs)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        // ensure empty state initially hidden until we decide
        findViewById<android.view.View>(R.id.emptyView).visibility = android.view.View.GONE

        // hook up player controls
        findViewById<android.widget.ImageButton>(R.id.btnPrev).setOnClickListener {
            if (bound) musicService?.playPrevious() else Unit
        }
        findViewById<android.widget.ImageButton>(R.id.btnPlayPause).setOnClickListener {
            if (!bound) return@setOnClickListener
            if (musicService?.isPlaying() == true) musicService?.pause() else musicService?.play()
            refreshNowPlaying()
        }
        findViewById<android.widget.ImageButton>(R.id.btnNext).setOnClickListener {
            if (bound) musicService?.playNext() else Unit
        }

        checkAndRequestPermission()
    }

    override fun onStart() {
        super.onStart()
        // bind to service so we can control playback
        Intent(this, MusicService::class.java).also { intent ->
            bindService(intent, connection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun checkAndRequestPermission() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (needed.isNotEmpty()) {
            requestPermission.launch(needed.toTypedArray())
        } else {
            loadSongs()
        }
    }

    private fun showPermissionRationale() {
        // show empty state and explain
        findViewById<android.view.View>(R.id.emptyView).visibility = android.view.View.VISIBLE

        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required_title)
            .setMessage(R.string.permission_required_message)
            .setPositiveButton(R.string.settings) { _, _ ->
                // open app settings using ktx toUri
                val uri = "package:$packageName".let { android.net.Uri.parse(it) }
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = uri
                })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadSongs() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { MediaRepository.loadAudioFiles(this@MainActivity) }

            // If no real songs found, provide a debug placeholder so UI can be validated
            val finalList = if (list.isEmpty() && isDebugBuild()) {
                val sample = Music(
                    id = 0L,
                    title = "示例音乐（无真实文件）",
                    artist = "调试模式",
                    album = "",
                    duration = 0L,
                    uri = android.net.Uri.EMPTY,
                    albumArtUri = null
                )
                listOf(sample)
            } else {
                list
            }

            adapter.setItems(finalList)
            lastLoadedList = finalList

            // toggle empty state view
            val empty = findViewById<android.view.View>(R.id.emptyView)
            empty.visibility = if (finalList.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

            // send playlist to service so it can manage queue
            if (finalList.isNotEmpty()) {
                // If bound, set playlist directly; otherwise start the service with the playlist extra
                if (bound) {
                    musicService?.setPlaylist(finalList)
                } else {
                    val intent = Intent(this@MainActivity, MusicService::class.java).apply {
                        action = MusicService.ACTION_PLAY
                        putParcelableArrayListExtra("playlist", ArrayList(finalList))
                    }
                    ContextCompat.startForegroundService(this@MainActivity, intent)
                }
            }
        }
    }

    private fun isDebugBuild(): Boolean {
        // Use ApplicationInfo.FLAG_DEBUGGABLE; avoids referencing generated BuildConfig
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun onSongClicked(song: Music, pos: Int) {
        // start service and play
        val intent = Intent(this, MusicService::class.java).apply {
            action = MusicService.ACTION_PLAY
            putExtra("song_uri", song.uri.toString())
            putExtra("song_pos", pos)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // cancel the job to stop coroutines
        mainJob.cancel()
    }
}
