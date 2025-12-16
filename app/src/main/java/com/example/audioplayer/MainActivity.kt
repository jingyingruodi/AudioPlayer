package com.example.audioplayer

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
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
    // use an explicit Job so we can cancel it cleanly in onDestroy
    private val mainJob = Job()
    private val scope = CoroutineScope(mainJob + Dispatchers.Main)

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
        // show empty state and explain
        findViewById<android.view.View>(R.id.emptyView).visibility = android.view.View.VISIBLE

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

            // toggle empty state view
            val empty = findViewById<android.view.View>(R.id.emptyView)
            empty.visibility = if (finalList.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

            // send playlist to service so it can manage queue
            if (finalList.isNotEmpty()) {
                val intent = Intent(this@MainActivity, MusicService::class.java).apply {
                    action = MusicService.ACTION_PLAY
                    putParcelableArrayListExtra("playlist", ArrayList(finalList))
                }
                ContextCompat.startForegroundService(this@MainActivity, intent)
            }
        }
    }

    private fun isDebugBuild(): Boolean {
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
