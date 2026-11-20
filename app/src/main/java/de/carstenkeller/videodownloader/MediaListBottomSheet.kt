package de.carstenkeller.videodownloader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import de.carstenkeller.videodownloader.databinding.FragmentMediaListBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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

    override fun onStart() {
        super.onStart()
        // Without this, the sheet opens in its collapsed "peek" state - fine for the original
        // short layout, but the added min-duration controls now push the download button below
        // that peek height, making it look like the button disappeared. Forcing it fully
        // expanded (and disabling the half-collapsed state entirely) keeps everything reachable.
        val sheet = (dialog as? BottomSheetDialog)?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = MediaListAdapter { id -> viewModel.toggleSelection(id) }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.minDurationSlider.value = viewModel.minDurationSeconds.value.toFloat()
        binding.minDurationSlider.addOnChangeListener { _, value, _ ->
            viewModel.setMinDurationSeconds(value.toInt())
        }

        binding.checkAll.setOnClickListener {
            viewModel.setAllSelected(true, visibleIds())
        }
        binding.uncheckAll.setOnClickListener {
            viewModel.setAllSelected(false, visibleIds())
        }
        binding.btnDownload.setOnClickListener { startDownload() }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.items, viewModel.minDurationSeconds) { items, minSeconds ->
                items to minSeconds
            }.collect { (items, minSeconds) ->
                binding.minDurationLabel.text = if (minSeconds <= 0) {
                    getString(R.string.min_duration_off)
                } else {
                    getString(R.string.min_duration_seconds, minSeconds)
                }

                val visible = items.filter { isVisibleForMinDuration(it, minSeconds) }
                adapter.submitList(visible)
                binding.emptyLabel.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
                val selectedCount = visible.count { it.selected }
                binding.btnDownload.text = getString(R.string.download_count, selectedCount)
                binding.btnDownload.isEnabled = selectedCount > 0 && !viewModel.downloading.value
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.downloading.collect { downloading ->
                binding.checkAll.isEnabled = !downloading
                binding.uncheckAll.isEnabled = !downloading
                binding.minDurationSlider.isEnabled = !downloading
                isCancelable = !downloading
                val selectedCount = visibleSelected().size
                binding.btnDownload.isEnabled = !downloading && selectedCount > 0
            }
        }
    }

    /** Ids of items currently shown under the minimum-length filter - used to scope "select
     * all"/"deselect all" and the actual download to what the user can actually see. */
    private fun visibleIds(): Set<String> {
        val minSeconds = viewModel.minDurationSeconds.value
        return viewModel.items.value.filter { isVisibleForMinDuration(it, minSeconds) }.map { it.id }.toSet()
    }

    private fun visibleSelected(): List<MediaItem> {
        val ids = visibleIds()
        return viewModel.items.value.filter { it.selected && it.id in ids }
    }

    private fun startDownload() {
        val selected = visibleSelected()
        if (selected.isEmpty()) return

        viewModel.setDownloading(true)
        selected.forEach { item ->
            viewModel.updateItem(item.id) { it.copy(status = DownloadStatus.DOWNLOADING, progress = -1) }
        }

        // DownloadService runs the batch independently of this fragment's own lifecycle - see
        // its class doc. viewModel picks progress/completion back up via DownloadEvents
        // (wired in MediaListViewModel.init) for as long as the app is around to show it; the
        // service's own notification is the only feedback once it isn't.
        DownloadService.start(requireContext().applicationContext, selected)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
