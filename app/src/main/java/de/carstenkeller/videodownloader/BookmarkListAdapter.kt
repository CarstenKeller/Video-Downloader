package de.carstenkeller.videodownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.carstenkeller.videodownloader.databinding.ItemBookmarkBinding

class BookmarkListAdapter(
    private val onClick: (Bookmark) -> Unit,
    private val onRemove: (Bookmark) -> Unit
) : ListAdapter<Bookmark, BookmarkListAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemBookmarkBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.title.text = item.title
        holder.binding.url.text = item.url
        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.btnRemove.setOnClickListener { onRemove(item) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Bookmark>() {
            override fun areItemsTheSame(oldItem: Bookmark, newItem: Bookmark) = oldItem.url == newItem.url
            override fun areContentsTheSame(oldItem: Bookmark, newItem: Bookmark) = oldItem == newItem
        }
    }
}
