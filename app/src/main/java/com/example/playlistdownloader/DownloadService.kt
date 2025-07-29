package com.example.playlistdownloader

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class DownloadService {
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()
    
    private val gson = Gson()
    
    data class VideoInfo(
        val title: String,
        val url: String,
        val audioUrl: String?,
        val videoUrl: String?,
        val duration: String
    )
    
    data class PlaylistInfo(
        val title: String,
        val videos: List<VideoInfo>
    )
    
    suspend fun getPlaylistInfo(playlistUrl: String): PlaylistInfo = withContext(Dispatchers.IO) {
        try {
            // Extract playlist ID
            val playlistId = extractPlaylistId(playlistUrl)
                ?: throw IOException("Invalid playlist URL")
            
            // Scrape playlist page
            val request = Request.Builder()
                .url("https://www.youtube.com/playlist?list=$playlistId")
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("Failed to fetch playlist")
            
            val html = response.body?.string() ?: throw IOException("Empty response")
            
            // Extract video information from HTML
            val videos = extractVideosFromHtml(html)
            val playlistTitle = extractPlaylistTitle(html)
            
            PlaylistInfo(
                title = playlistTitle,
                videos = videos
            )
        } catch (e: Exception) {
            throw IOException("Failed to extract playlist: ${e.message}")
        }
    }
    
    private fun extractPlaylistId(url: String): String? {
        val regex = Regex("list=([a-zA-Z0-9_-]+)")
        return regex.find(url)?.groupValues?.get(1)
    }
    
    private fun extractPlaylistTitle(html: String): String {
        val titlePattern = Pattern.compile("\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"")
        val matcher = titlePattern.matcher(html)
        return if (matcher.find()) {
            URLDecoder.decode(matcher.group(1), "UTF-8")
        } else {
            "YouTube Playlist"
        }
    }
    
    private fun extractVideosFromHtml(html: String): List<VideoInfo> {
        val videos = mutableListOf<VideoInfo>()
        
        // Look for video data in the HTML
        val videoPattern = Pattern.compile("\"videoId\":\"([^\"]+)\".*?\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"")
        val matcher = videoPattern.matcher(html)
        
        while (matcher.find()) {
            val videoId = matcher.group(1)
            val title = URLDecoder.decode(matcher.group(2), "UTF-8")
            val videoUrl = "https://www.youtube.com/watch?v=$videoId"
            
            videos.add(
                VideoInfo(
                    title = title,
                    url = videoUrl,
                    audioUrl = null, // Will be resolved during download
                    videoUrl = videoUrl,
                    duration = "Unknown"
                )
            )
        }
        
        return videos
    }
    
    suspend fun downloadVideo(
        videoInfo: VideoInfo,
        downloadFolder: String,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // For demonstration, we'll create a placeholder file
            // In a real implementation, you'd need to:
            // 1. Extract actual stream URLs from YouTube
            // 2. Download the audio stream
            // 3. Convert to MP3 if needed
            
            val fileName = "${videoInfo.title.replace(Regex("[^a-zA-Z0-9\\s-_]"), "")}.txt"
            val file = File(downloadFolder, fileName)
            
            // Simulate download progress
            for (i in 0..100 step 10) {
                onProgress(i)
                Thread.sleep(100) // Simulate download time
            }
            
            // Create a placeholder file with video info
            file.writeText("""
                Video Title: ${videoInfo.title}
                Video URL: ${videoInfo.url}
                
                This is a placeholder file.
                To implement real downloading, you would need to:
                1. Extract stream URLs from YouTube's player response
                2. Download the actual audio/video streams
                3. Convert formats as needed
                
                This requires more complex YouTube API integration or
                using tools like yt-dlp through a web service.
            """.trimIndent())
            
            true
        } catch (e: Exception) {
            false
        }
    }
}