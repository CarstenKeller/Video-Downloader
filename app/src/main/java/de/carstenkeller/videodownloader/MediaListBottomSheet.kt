package de.carstenkeller.videodownloader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import de.carstenkeller.videodownloader.databinding.FragmentMediaListBinding
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MediaListBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: MediaListViewModel by activityViewModels()
    private var _binding: FragmentMediaListBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: MediaListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = MediaListAdapter { id -> viewModel.toggleSelection(id) }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.checkAll.setOnClickListener { viewModel.setAllSelected(true) }
        binding.uncheckAll.setOnClickListener { viewModel.setAllSelected(false) }
        binding.btnDownload.setOnClickListener { startDownload() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.items.collect { items ->
                adapter.submitList(items)
                binding.emptyLabel.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                val selectedCount = items.count { it.selected }
                binding.btnDownload.text = getString(R.string.download_count, selectedCount)
                binding.btnDownload.isEnabled = selectedCount > 0 && !viewModel.downloading.value
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.downloading.collect { downloading ->
                binding.checkAll.isEnabled = !downloading
                binding.uncheckAll.isEnabled = !downloading
                isCancelable = !downloading
                val selectedCount = viewModel.items.value.count { it.selected }
                binding.btnDownload.isEnabled = !downloading && selectedCount > 0
            }
        }
    }

    private fun startDownload() {
        val selected = viewModel.items.value.filter { it.selected }
        if (selected.isEmpty()) return

        viewModel.setDownloading(true)
        selected.forEach { item ->
            viewModel.updateItem(item.id) { it.copy(status = DownloadStatus.DOWNLOADING, progress = -1) }
        }

        val client = OkHttpClient()
        val coordinator = DownloadCoordinator(requireContext().applicationContext, client)

        viewLifecycleOwner.lifecycleScope.launch {
            coordinator.downloadAll(
                items = selected,
                onProgress = { id, pct -> viewModel.updateItem(id) { it.copy(progress = pct) } },
                onDone = { id, success, error ->
                    viewModel.updateItem(id) {
                        it.copy(
                            status = if (success) DownloadStatus.DONE else DownloadStatus.ERROR,
                            errorMessage = error
                        )
                    }
                }
            )
            viewModel.setDownloading(false)
            val results = viewModel.items.value
            val successCount = results.count { it.status == DownloadStatus.DONE }
            Toast.makeText(
                requireContext(),
                getString(R.string.download_summary, successCount, selected.size),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
