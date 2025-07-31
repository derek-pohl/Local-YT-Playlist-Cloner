package com.example.playlistdownloader

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.File

data class TrackedPlaylist(
    val id: String,
    val url: String,
    val title: String,
    val downloadFolder: String,
    val lastSync: Long = 0,
    val syncInterval: Long = 3600000, // 1 hour in milliseconds
    val isActive: Boolean = true
)

data class TrackedVideo(
    val videoId: String,
    val title: String,
    val url: String,
    val fileName: String,
    val downloadedAt: Long = 0,
    val isDownloaded: Boolean = false
)

class PlaylistTracker(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("playlist_tracker", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val downloadService = DownloadService()
    private var syncJob: Job? = null
    
    // Progress callback
    var onSyncProgress: ((String, Boolean, Int, String) -> Unit)? = null
    
    companion object {
        private const val TRACKED_PLAYLISTS_KEY = "tracked_playlists"
        private const val PLAYLIST_VIDEOS_PREFIX = "playlist_videos_"
    }
    
    fun addPlaylistToTrack(playlistUrl: String, downloadFolder: String): String {
        val playlistId = extractPlaylistId(playlistUrl) ?: throw IllegalArgumentException("Invalid playlist URL")
        
        val trackedPlaylist = TrackedPlaylist(
            id = playlistId,
            url = playlistUrl,
            title = "Loading...",
            downloadFolder = downloadFolder,
            lastSync = 0 // Force immediate sync
        )
        
        saveTrackedPlaylist(trackedPlaylist)
        startSyncService()
        
        // Trigger immediate sync for new playlist
        CoroutineScope(Dispatchers.IO).launch {
            syncPlaylist(trackedPlaylist)
        }
        
        return playlistId
    }
    
    fun removePlaylistFromTracking(playlistId: String) {
        val playlists = getTrackedPlaylists().toMutableList()
        playlists.removeAll { it.id == playlistId }
        saveTrackedPlaylists(playlists)
        
        // Clean up downloaded files
        val videos = getPlaylistVideos(playlistId)
        videos.forEach { video ->
            if (video.isDownloaded) {
                val file = File(getTrackedPlaylist(playlistId)?.downloadFolder, video.fileName)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
        
        // Remove video tracking data
        prefs.edit().remove("$PLAYLIST_VIDEOS_PREFIX$playlistId").apply()
    }
    
    fun getTrackedPlaylists(): List<TrackedPlaylist> {
        val json = prefs.getString(TRACKED_PLAYLISTS_KEY, "[]")
        val type = object : TypeToken<List<TrackedPlaylist>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }
    
    fun getTrackedPlaylist(playlistId: String): TrackedPlaylist? {
        return getTrackedPlaylists().find { it.id == playlistId }
    }
    
    fun getPlaylistVideos(playlistId: String): List<TrackedVideo> {
        val json = prefs.getString("$PLAYLIST_VIDEOS_PREFIX$playlistId", "[]")
        val type = object : TypeToken<List<TrackedVideo>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }
    
    fun startSyncService() {
        syncJob?.cancel()
        syncJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    syncAllPlaylists()
                    delay(60000) // Check every minute
                } catch (e: Exception) {
                    // Log error but continue
                    delay(60000)
                }
            }
        }
    }
    
    fun stopSyncService() {
        syncJob?.cancel()
    }
    
    suspend fun forceSyncPlaylist(playlistId: String): Boolean {
        val playlist = getTrackedPlaylist(playlistId) ?: return false
        return try {
            syncPlaylist(playlist)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun syncAllPlaylists() {
        val playlists = getTrackedPlaylists().filter { it.isActive }
        
        for (playlist in playlists) {
            if (shouldSync(playlist)) {
                syncPlaylist(playlist)
            }
        }
    }
    
    private fun shouldSync(playlist: TrackedPlaylist): Boolean {
        val timeSinceLastSync = System.currentTimeMillis() - playlist.lastSync
        return timeSinceLastSync >= playlist.syncInterval || playlist.lastSync == 0L
    }
    
    private suspend fun syncPlaylist(playlist: TrackedPlaylist) {
        try {
            println("Starting sync for playlist: ${playlist.title}")
            onSyncProgress?.invoke(playlist.id, true, 0, "Analyzing playlist...")
            
            // Get current playlist state from YouTube
            val currentPlaylistInfo = downloadService.getPlaylistInfo(playlist.url)
            println("Found ${currentPlaylistInfo.videos.size} videos in playlist")
            
            onSyncProgress?.invoke(playlist.id, true, 10, "Found ${currentPlaylistInfo.videos.size} videos")
            
            val currentVideos = currentPlaylistInfo.videos.map { video ->
                TrackedVideo(
                    videoId = extractVideoId(video.url) ?: "",
                    title = video.title,
                    url = video.url,
                    fileName = "${video.title.replace(Regex("[^a-zA-Z0-9\\s-_]"), "")}.mp3"
                )
            }
            
            // Get previously tracked videos
            val previousVideos = getPlaylistVideos(playlist.id)
            val previousVideoIds = previousVideos.map { it.videoId }.toSet()
            val currentVideoIds = currentVideos.map { it.videoId }.toSet()
            
            // Find videos to add (new videos in playlist)
            val videosToAdd = currentVideos.filter { it.videoId !in previousVideoIds }
            
            // Find videos to remove (videos removed from playlist)
            val videosToRemove = previousVideos.filter { it.videoId !in currentVideoIds }
            
            // Remove deleted videos with progress tracking
            if (videosToRemove.isNotEmpty()) {
                onSyncProgress?.invoke(playlist.id, true, 15, "Removing ${videosToRemove.size} deleted videos...")
                println("Removing ${videosToRemove.size} videos that were deleted from playlist")
                
                for ((index, video) in videosToRemove.withIndex()) {
                    val progress = 15 + (index * 5 / videosToRemove.size.coerceAtLeast(1))
                    onSyncProgress?.invoke(playlist.id, true, progress, "Deleting: ${video.title}")
                    
                    if (video.isDownloaded) {
                        val file = File(playlist.downloadFolder, video.fileName)
                        if (file.exists()) {
                            val fileSize = file.length()
                            val deleted = file.delete()
                            if (deleted) {
                                println("✅ Successfully deleted: ${file.name} ($fileSize bytes)")
                            } else {
                                println("❌ Failed to delete: ${file.absolutePath}")
                            }
                        } else {
                            println("⚠️ File not found for deletion: ${file.absolutePath}")
                        }
                    } else {
                        println("ℹ️ Video was not downloaded, skipping deletion: ${video.title}")
                    }
                }
            }
            
            // Download new videos
            val updatedVideos = mutableListOf<TrackedVideo>()
            
            // Keep existing videos that are still in playlist
            updatedVideos.addAll(previousVideos.filter { it.videoId in currentVideoIds })
            
            // Add and download new videos
            println("Downloading ${videosToAdd.size} new videos to: ${playlist.downloadFolder}")
            
            if (videosToAdd.isEmpty()) {
                onSyncProgress?.invoke(playlist.id, true, 85, "No new videos to download")
            } else {
                onSyncProgress?.invoke(playlist.id, true, 20, "Downloading ${videosToAdd.size} new videos...")
            }
            
            for ((index, newVideo) in videosToAdd.withIndex()) {
                println("Downloading: ${newVideo.title}")
                
                val progressBase = 20 + (index * 65 / videosToAdd.size.coerceAtLeast(1))
                onSyncProgress?.invoke(playlist.id, true, progressBase, "Downloading: ${newVideo.title}")
                
                val videoInfo = DownloadService.VideoInfo(
                    title = newVideo.title,
                    url = newVideo.url,
                    audioUrl = newVideo.url,
                    videoUrl = newVideo.url,
                    duration = "Unknown"
                )
                
                val downloadSuccess = downloadService.downloadVideo(
                    videoInfo,
                    playlist.downloadFolder
                ) { progress ->
                    val totalProgress = progressBase + (progress * 65 / 100 / videosToAdd.size.coerceAtLeast(1))
                    onSyncProgress?.invoke(playlist.id, true, totalProgress, "Downloading: ${newVideo.title} ($progress%)")
                }
                
                println("Download ${if (downloadSuccess) "successful" else "failed"} for: ${newVideo.title}")
                
                updatedVideos.add(
                    newVideo.copy(
                        isDownloaded = downloadSuccess,
                        downloadedAt = if (downloadSuccess) System.currentTimeMillis() else 0
                    )
                )
            }
            
            // Save updated video list
            savePlaylistVideos(playlist.id, updatedVideos)
            
            // Update playlist last sync time and title
            onSyncProgress?.invoke(playlist.id, true, 90, "Finalizing sync...")
            
            val updatedPlaylist = playlist.copy(
                title = currentPlaylistInfo.title,
                lastSync = System.currentTimeMillis()
            )
            updateTrackedPlaylist(updatedPlaylist)
            
            // Show final summary
            val addedCount = videosToAdd.size
            val removedCount = videosToRemove.size
            val summaryText = when {
                addedCount > 0 && removedCount > 0 -> "Sync completed! Added $addedCount, removed $removedCount videos"
                addedCount > 0 -> "Sync completed! Added $addedCount new videos"
                removedCount > 0 -> "Sync completed! Removed $removedCount videos"
                else -> "Sync completed! Playlist is up to date"
            }
            
            onSyncProgress?.invoke(playlist.id, false, 100, summaryText)
            
        } catch (e: Exception) {
            // Handle sync error - could notify user
            onSyncProgress?.invoke(playlist.id, false, 0, "Sync failed: ${e.message}")
        }
    }
    
    private fun extractPlaylistId(url: String): String? {
        val regex = Regex("list=([a-zA-Z0-9_-]+)")
        return regex.find(url)?.groupValues?.get(1)
    }
    
    private fun extractVideoId(url: String): String? {
        val regex = Regex("(?:v=|/)([a-zA-Z0-9_-]{11})")
        return regex.find(url)?.groupValues?.get(1)
    }
    
    private fun saveTrackedPlaylist(playlist: TrackedPlaylist) {
        val playlists = getTrackedPlaylists().toMutableList()
        val existingIndex = playlists.indexOfFirst { it.id == playlist.id }
        
        if (existingIndex >= 0) {
            playlists[existingIndex] = playlist
        } else {
            playlists.add(playlist)
        }
        
        saveTrackedPlaylists(playlists)
    }
    
    private fun updateTrackedPlaylist(playlist: TrackedPlaylist) {
        saveTrackedPlaylist(playlist)
    }
    
    private fun saveTrackedPlaylists(playlists: List<TrackedPlaylist>) {
        val json = gson.toJson(playlists)
        prefs.edit().putString(TRACKED_PLAYLISTS_KEY, json).apply()
    }
    
    private fun savePlaylistVideos(playlistId: String, videos: List<TrackedVideo>) {
        val json = gson.toJson(videos)
        prefs.edit().putString("$PLAYLIST_VIDEOS_PREFIX$playlistId", json).apply()
    }
}