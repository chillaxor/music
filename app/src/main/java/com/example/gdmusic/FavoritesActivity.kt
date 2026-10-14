package com.example.gdmusic

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gdmusic.PlaylistStore.Playlist

class FavoritesActivity : AppCompatActivity() {

    private lateinit var adapter: FavAdapter
    private lateinit var emptyText: TextView
    private lateinit var totalText: TextView
    private lateinit var tabsContainer: LinearLayout
    private var currentPlaylistId = PlaylistStore.DEFAULT_ID
    private val playlists = mutableListOf<Playlist>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_favorites)

        emptyText = findViewById(R.id.emptyText)
        totalText = findViewById(R.id.totalText)
        tabsContainer = findViewById(R.id.playlistTabs)

        adapter = FavAdapter(
            onPlay = { playSong(it) },
            onUnfav = { unfav(it) }
        )
        findViewById<RecyclerView>(R.id.favList).apply {
            layoutManager = LinearLayoutManager(this@FavoritesActivity)
            adapter = this@FavoritesActivity.adapter
        }

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.newPlaylistBtn).setOnClickListener { createPlaylist() }
        findViewById<View>(R.id.playAllBtn).setOnClickListener { playAll() }

        loadPlaylists()
    }

    override fun onResume() {
        super.onResume()
        loadPlaylists() // 从主页新增/取消收藏、或新建歌单后回来刷新
    }

    private fun loadPlaylists() {
        playlists.clear(); playlists.addAll(PlaylistStore.getPlaylists(this))
        if (playlists.none { it.id == currentPlaylistId }) currentPlaylistId = PlaylistStore.DEFAULT_ID
        renderTabs()
        refresh()
    }

    private fun renderTabs() {
        tabsContainer.removeAllViews()
        val padH = (12 * resources.displayMetrics.density).toInt()
        val padV = (6 * resources.displayMetrics.density).toInt()
        playlists.forEach { pl ->
            val tv = TextView(this)
            tv.text = pl.name
            tv.setPadding(padH, padV, padH, padV)
            tv.textSize = 14f
            tv.tag = pl.id
            val sel = pl.id == currentPlaylistId
            styleTab(tv, sel)
            tv.setOnClickListener {
                currentPlaylistId = pl.id
                renderTabs()
                refresh()
            }
            tv.setOnLongClickListener {
                if (pl.id == PlaylistStore.DEFAULT_ID) {
                    renamePlaylist(pl)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(pl.name)
                        .setItems(arrayOf("重命名", "删除歌单")) { _, which ->
                            if (which == 0) renamePlaylist(pl) else deletePlaylist(pl)
                        }.show()
                }
                true
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (8 * resources.displayMetrics.density).toInt()
            tabsContainer.addView(tv, lp)
        }
    }

    private fun styleTab(tv: TextView, selected: Boolean) {
        val bg = GradientDrawable()
        bg.cornerRadius = (18 * resources.displayMetrics.density)
        if (selected) {
            bg.setColor(ContextCompat.getColor(this, R.color.primary))
            tv.setTextColor(ContextCompat.getColor(this, R.color.on_primary))
        } else {
            bg.setColor(ContextCompat.getColor(this, R.color.surface))
            tv.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
        }
        tv.background = bg
    }

    private fun refresh() {
        val items = PlaylistStore.getSongs(this, currentPlaylistId)
        adapter.submit(items)
        totalText.text = "${playlists.first { it.id == currentPlaylistId }.name} · 共 ${items.size} 首"
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.favList).visibility =
            if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun playSong(s: Song) {
        val songs = PlaylistStore.getSongs(this, currentPlaylistId)
        val idx = songs.indexOfFirst { it.key() == s.key() }.coerceAtLeast(0)
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("play_queue_json", songsToJsonArray(songs))
            putExtra("play_index", idx)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun playAll() {
        val songs = PlaylistStore.getSongs(this, currentPlaylistId)
        if (songs.isEmpty()) return
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("play_queue_json", songsToJsonArray(songs))
            putExtra("play_index", 0)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun unfav(s: Song) {
        PlaylistStore.removeSong(this, currentPlaylistId, s)
        refresh()
    }

    private fun createPlaylist() {
        val edit = android.widget.EditText(this); edit.hint = "歌单名称"
        AlertDialog.Builder(this)
            .setTitle("新建歌单")
            .setView(edit)
            .setPositiveButton("创建") { _, _ ->
                val id = PlaylistStore.createPlaylist(this, edit.text.toString())
                currentPlaylistId = id
                loadPlaylists()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renamePlaylist(pl: Playlist) {
        val edit = android.widget.EditText(this); edit.setText(pl.name)
        AlertDialog.Builder(this)
            .setTitle("重命名歌单")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                PlaylistStore.renamePlaylist(this, pl.id, edit.text.toString())
                loadPlaylists()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deletePlaylist(pl: Playlist) {
        AlertDialog.Builder(this)
            .setTitle("删除歌单")
            .setMessage("确定删除「${pl.name}」？里面的歌曲会一起移除。")
            .setPositiveButton("删除") { _, _ ->
                PlaylistStore.deletePlaylist(this, pl.id)
                if (currentPlaylistId == pl.id) currentPlaylistId = PlaylistStore.DEFAULT_ID
                loadPlaylists()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    class FavAdapter(
        private val onPlay: (Song) -> Unit,
        private val onUnfav: (Song) -> Unit
    ) : RecyclerView.Adapter<FavAdapter.VH>() {
        private val data = mutableListOf<Song>()
        fun submit(l: List<Song>) { data.clear(); data.addAll(l); notifyDataSetChanged() }
        override fun getItemCount() = data.size
        override fun onCreateViewHolder(p: ViewGroup, v: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_favorite, p, false))
        override fun onBindViewHolder(h: VH, p: Int) = h.bind(data[p])
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val root = v.findViewById<View>(R.id.itemRoot)
            private val name = v.findViewById<TextView>(R.id.favName)
            private val artist = v.findViewById<TextView>(R.id.favArtist)
            private val source = v.findViewById<TextView>(R.id.favSource)
            private val unfav = v.findViewById<TextView>(R.id.favUnfav)
            fun bind(x: Song) {
                name.text = x.name.ifBlank { "未知歌曲" }
                val a = x.artist.ifBlank { null }
                val al = x.album.ifBlank { null }
                artist.text = listOfNotNull(a, al).joinToString(" · ")
                source.text = sourceLabel(x.source).ifBlank { "未知" }
                source.setTextColor(ContextCompat.getColor(source.context, R.color.primary))
                root.setOnClickListener { onPlay(x) }
                unfav.setOnClickListener { onUnfav(x) }
            }
        }
    }
}
