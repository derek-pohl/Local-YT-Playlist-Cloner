package com.example.playlistdownloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var urlInput: EditText
    private lateinit var downloadButton: Button
    private lateinit var trackButton: Button
    private lateinit var folderButton: Button
    private lateinit var statusText: TextView
    private lateinit var folderPathText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var trackedPlaylistsRecycler: RecyclerView
    private lateinit var noPlaylistsText: TextView
    
    private var selectedFolderUri: Uri? = null
    private var selectedFolderPath: String = ""
    private lateinit var downloadService: DownloadService
    private lateinit var playlistTracker: PlaylistTracker
    private lateinit var playlistAdapter: TrackedPlaylistAdapter
    private val syncProgressMap = mutableMapOf<String, PlaylistSyncProgress>()

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedFolderUri = uri
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                
                val path = getPathFromUri(uri)
                selectedFolderPath = path
                folderPathText.text = "Download folder: $path"
                statusText.text = "Folder selected successfully"
                saveFolderSelection(path)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        downloadButton = findViewById(R.id.downloadButton)
        trackButton = findViewById(R.id.trackButton)
        folderButton = findViewById(R.id.folderButton)
        statusText = findViewById(R.id.statusText)
        folderPathText = findViewById(R.id.folderPathText)
        progressBar = findViewById(R.id.progressBar)
        trackedPlaylistsRecycler = findViewById(R.id.trackedPlaylistsRecycler)
        noPlaylistsText = findViewById(R.id.noPlaylistsText)
        
        // Initialize services
        downloadService = DownloadService()
        playlistTracker = PlaylistTracker(this)
        
        // Setup progress callback
        playlistTracker.onSyncProgress = { playlistId, isLoading, progress, progressText ->
            runOnUiThread {
                syncProgressMap[playlistId] = PlaylistSyncProgress(
                    playlistId = playlistId,
                    isLoading = isLoading,
                    progress = progress,
                    progressText = progressText
                )
                playlistAdapter.updateSyncProgress(syncProgressMap.toMap())
            }
        }
        
        // Setup RecyclerView
        setupTrackedPlaylistsRecycler()
        updateTrackedPlaylistsUI()

        // Load saved folder or set default
        loadSavedFolder()

        folderButton.setOnClickListener {
            openFolderPicker()
        }

        trackButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            when {
                url.isEmpty() -> {
                    statusText.text = "Please enter a playlist URL"
                    Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show()
                }
                !isValidYouTubeUrl(url) -> {
                    statusText.text = "Invalid YouTube playlist URL"
                    Toast.makeText(this, "Please enter a valid YouTube playlist URL", Toast.LENGTH_SHORT).show()
                }
                selectedFolderPath.isEmpty() -> {
                    statusText.text = "Please select a download folder"
                    Toast.makeText(this, "Please select a download folder", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    startTracking(url)
                }
            }
        }

        downloadButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            when {
                url.isEmpty() -> {
                    statusText.text = "Please enter a playlist URL"
                    Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show()
                }
                !isValidYouTubeUrl(url) -> {
                    statusText.text = "Invalid YouTube playlist URL"
                    Toast.makeText(this, "Please enter a valid YouTube playlist URL", Toast.LENGTH_SHORT).show()
                }
                selectedFolderPath.isEmpty() -> {
                    statusText.text = "Please select a download folder"
                    Toast.makeText(this, "Please select a download folder", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    startDownload(url)
                }
            }
        }

        checkStoragePermission()
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or 
                   Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                   Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        folderPickerLauncher.launch(intent)
    }

    private fun getPathFromUri(uri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        return if (docId.contains(":")) {
            val split = docId.split(":")
            "/storage/emulated/0/${split[1]}"
        } else {
            "/storage/emulated/0/"
        }
    }

    private fun isValidYouTubeUrl(url: String): Boolean {
        return url.contains("youtube.com/playlist") || 
               url.contains("youtu.be/") ||
               url.contains("youtube.com/watch")
    }

    private fun startDownload(url: String) {
        downloadButton.isEnabled = false
        progressBar.visibility = ProgressBar.VISIBLE
        statusText.text = "Analyzing playlist..."
        
        lifecycleScope.launch {
            try {
                // Get playlist info
                val playlistInfo = downloadService.getPlaylistInfo(url)
                statusText.text = "Found ${playlistInfo.videos.size} videos in '${playlistInfo.title}'"
                
                var downloadedCount = 0
                val totalVideos = playlistInfo.videos.size
                
                // Download each video
                for ((index, video) in playlistInfo.videos.withIndex()) {
                    statusText.text = "Downloading ${index + 1}/$totalVideos: ${video.title}"
                    
                    val success = downloadService.downloadVideo(
                        video,
                        selectedFolderPath
                    ) { progress ->
                        runOnUiThread {
                            progressBar.progress = progress
                        }
                    }
                    
                    if (success) {
                        downloadedCount++
                    }
                    
                    runOnUiThread {
                        statusText.text = "Downloaded $downloadedCount/$totalVideos videos"
                    }
                }
                
                // Download complete
                runOnUiThread {
                    progressBar.visibility = ProgressBar.GONE
                    downloadButton.isEnabled = true
                    statusText.text = "Download complete! $downloadedCount/$totalVideos videos downloaded to:\n$selectedFolderPath"
                    Toast.makeText(this@MainActivity, "Download complete!", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = ProgressBar.GONE
                    downloadButton.isEnabled = true
                    statusText.text = "Error: ${e.message}"
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupTrackedPlaylistsRecycler() {
        playlistAdapter = TrackedPlaylistAdapter(
            playlists = emptyList(),
            syncProgress = emptyMap(),
            onRemoveClick = { playlist ->
                playlistTracker.removePlaylistFromTracking(playlist.id)
                syncProgressMap.remove(playlist.id)
                updateTrackedPlaylistsUI()
                Toast.makeText(this, "Playlist removed from tracking", Toast.LENGTH_SHORT).show()
            },
            onSyncClick = { playlist ->
                lifecycleScope.launch {
                    playlistTracker.forceSyncPlaylist(playlist.id)
                    // Progress updates will be handled by the callback
                    updateTrackedPlaylistsUI()
                }
            }
        )
        
        trackedPlaylistsRecycler.layoutManager = LinearLayoutManager(this)
        trackedPlaylistsRecycler.adapter = playlistAdapter
    }
    
    private fun updateTrackedPlaylistsUI() {
        val trackedPlaylists = playlistTracker.getTrackedPlaylists()
        
        if (trackedPlaylists.isEmpty()) {
            trackedPlaylistsRecycler.visibility = View.GONE
            noPlaylistsText.visibility = View.VISIBLE
        } else {
            trackedPlaylistsRecycler.visibility = View.VISIBLE
            noPlaylistsText.visibility = View.GONE
            playlistAdapter.updatePlaylists(trackedPlaylists)
        }
    }
    
    private fun startTracking(url: String) {
        try {
            statusText.text = "Adding playlist to tracking and starting initial sync..."
            val playlistId = playlistTracker.addPlaylistToTrack(url, selectedFolderPath)
            statusText.text = "Playlist added to tracking! Initial sync started.\nFiles will be saved to: $selectedFolderPath"
            Toast.makeText(this, "Playlist tracking started! Check folder for downloads.", Toast.LENGTH_LONG).show()
            urlInput.text.clear()
            updateTrackedPlaylistsUI()
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
            Toast.makeText(this, "Failed to track playlist: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateTrackedPlaylistsUI()
        playlistTracker.startSyncService()
    }
    
    override fun onPause() {
        super.onPause()
        // Keep sync service running in background
    }
    
    override fun onDestroy() {
        super.onDestroy()
        playlistTracker.stopSyncService()
    }

    private fun loadSavedFolder() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        selectedFolderPath = prefs.getString("selected_folder", 
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        ) ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        
        folderPathText.text = "Download folder: $selectedFolderPath"
    }
    
    private fun saveFolderSelection(path: String) {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        prefs.edit().putString("selected_folder", path).apply()
    }

    private fun checkStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }
}