package com.example.audioplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

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

        checkAndRequestPermission()
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
        AlertDialog.Builder(this)
            .setTitle("权限需要")
            .setMessage("应用需要读取媒体文件以显示音乐。请在设置中授予权限。")
            .setPositiveButton("设置") { _, _ ->
                // open app settings
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadSongs() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { MediaRepository.loadAudioFiles(this@MainActivity) }
            adapter.setItems(list)
            // send playlist to service so it can manage queue
            if (list.isNotEmpty()) {
                val intent = Intent(this@MainActivity, MusicService::class.java).apply {
                    action = MusicService.ACTION_PLAY
                    putParcelableArrayListExtra("playlist", ArrayList(list))
                }
                ContextCompat.startForegroundService(this@MainActivity, intent)
            }
        }
    }

    private fun onSongClicked(song: com.example.audioplayer.Music, pos: Int) {
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
        scope.coroutineContext.cancelChildren()
    }
}
