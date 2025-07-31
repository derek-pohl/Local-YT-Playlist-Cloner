package com.example.playlistdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

data class PlaylistSyncProgress(
    val playlistId: String,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val progressText: String = ""
)

class TrackedPlaylistAdapter(
    private var playlists: List<TrackedPlaylist>,
    private var syncProgress: Map<String, PlaylistSyncProgress> = emptyMap(),
    private val onRemoveClick: (TrackedPlaylist) -> Unit,
    private val onSyncClick: (TrackedPlaylist) -> Unit
) : RecyclerView.Adapter<TrackedPlaylistAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.playlistTitle)
        val statusText: TextView = view.findViewById(R.id.playlistStatus)
        val lastSyncText: TextView = view.findViewById(R.id.lastSyncText)
        val progressText: TextView = view.findViewById(R.id.progressText)
        val syncProgressBar: ProgressBar = view.findViewById(R.id.syncProgressBar)
        val removeButton: Button = view.findViewById(R.id.removeButton)
        val syncButton: Button = view.findViewById(R.id.syncButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tracked_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = playlists[position]
        val progress = syncProgress[playlist.id]
        
        holder.titleText.text = playlist.title
        holder.statusText.text = if (playlist.isActive) "🟢 Active" else "🔴 Paused"
        
        val lastSyncText = if (playlist.lastSync > 0) {
            val date = Date(playlist.lastSync)
            val formatter = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            "Last sync: ${formatter.format(date)}"
        } else {
            "Never synced"
        }
        holder.lastSyncText.text = lastSyncText
        
        // Handle progress display
        if (progress?.isLoading == true) {
            holder.progressText.visibility = View.VISIBLE
            holder.syncProgressBar.visibility = View.VISIBLE
            holder.progressText.text = progress.progressText
            holder.syncProgressBar.progress = progress.progress
            holder.syncButton.isEnabled = false
            holder.syncButton.text = "⏳ Syncing..."
        } else {
            holder.progressText.visibility = View.GONE
            holder.syncProgressBar.visibility = View.GONE
            holder.syncButton.isEnabled = true
            holder.syncButton.text = "🔄 Sync Now"
        }
        
        holder.removeButton.setOnClickListener {
            onRemoveClick(playlist)
        }
        
        holder.syncButton.setOnClickListener {
            if (progress?.isLoading != true) {
                onSyncClick(playlist)
            }
        }
    }

    override fun getItemCount() = playlists.size

    fun updatePlaylists(newPlaylists: List<TrackedPlaylist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }
    
    fun updateSyncProgress(newSyncProgress: Map<String, PlaylistSyncProgress>) {
        syncProgress = newSyncProgress
        notifyDataSetChanged()
    }
}