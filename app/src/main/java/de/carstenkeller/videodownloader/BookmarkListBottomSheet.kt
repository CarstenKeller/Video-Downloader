package de.carstenkeller.videodownloader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import de.carstenkeller.videodownloader.databinding.FragmentBookmarkListBinding

class BookmarkListBottomSheet : BottomSheetDialogFragment() {

    private var _binding: FragmentBookmarkListBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: BookmarkStore
    private lateinit var adapter: BookmarkListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBookmarkListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = BookmarkStore(requireContext().applicationContext)

        adapter = BookmarkListAdapter(
            onClick = { bookmark ->
                parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_URL to bookmark.url))
                dismiss()
            },
            onRemove = { bookmark ->
                store.remove(bookmark.url)
                refresh()
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        refresh()
    }

    private fun refresh() {
        val bookmarks = store.getAll()
        adapter.submitList(bookmarks)
        binding.emptyLabel.visibility = if (bookmarks.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (bookmarks.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BookmarkListBottomSheet"
        const val REQUEST_KEY = "bookmark_selected"
        const val RESULT_URL = "url"
    }
}
