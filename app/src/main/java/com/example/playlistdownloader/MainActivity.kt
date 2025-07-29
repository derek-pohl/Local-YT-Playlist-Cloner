package com.example.playlistdownloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
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
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var urlInput: EditText
    private lateinit var downloadButton: Button
    private lateinit var folderButton: Button
    private lateinit var statusText: TextView
    private lateinit var folderPathText: TextView
    private lateinit var progressBar: ProgressBar
    
    private var selectedFolderUri: Uri? = null
    private var selectedFolderPath: String = ""
    private lateinit var downloadService: DownloadService

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
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        downloadButton = findViewById(R.id.downloadButton)
        folderButton = findViewById(R.id.folderButton)
        statusText = findViewById(R.id.statusText)
        folderPathText = findViewById(R.id.folderPathText)
        progressBar = findViewById(R.id.progressBar)
        
        // Initialize download service
        downloadService = DownloadService()

        // Set default download folder
        val defaultPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        selectedFolderPath = defaultPath
        folderPathText.text = "Download folder: $defaultPath"

        folderButton.setOnClickListener {
            openFolderPicker()
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

    private fun checkStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }
}