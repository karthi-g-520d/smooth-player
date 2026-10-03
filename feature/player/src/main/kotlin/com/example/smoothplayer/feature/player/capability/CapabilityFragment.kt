package com.example.smoothplayer.feature.player.capability

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.smoothplayer.engine.api.model.CapabilityCheck
import com.example.smoothplayer.engine.api.model.CapabilityCheckId
import com.example.smoothplayer.feature.player.R
import com.example.smoothplayer.feature.player.databinding.FragmentCapabilityBinding
import kotlinx.coroutines.launch

/** Thin view: binds widgets, forwards clicks as events and renders [CapabilityUiState]. */
public class CapabilityFragment(
    private val viewModelFactory: ViewModelProvider.Factory,
) : Fragment(R.layout.fragment_capability) {

    // HEADER
    private var _binding: FragmentCapabilityBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: CapabilityViewModel by lazy {
        ViewModelProvider(this, viewModelFactory)[CapabilityViewModel::class.java]
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCapabilityBinding.bind(view)
        bindListeners()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    // BODY
    private fun render(state: CapabilityUiState) = with(binding) {
        checksText.text = when (state.phase) {
            CapabilityUiState.Phase.Probing -> getString(R.string.capability_probing)
            CapabilityUiState.Phase.Failed -> getString(R.string.capability_probe_failed, state.error.orEmpty())
            CapabilityUiState.Phase.Ready -> state.checks.joinToString(separator = "\n") { it.toLine() }
        }
        reportText.text = state.reportJson
        exportButton.isEnabled =
            state.phase == CapabilityUiState.Phase.Ready && state.export != ExportStatus.Exporting
        exportStatusText.text = when (val export = state.export) {
            ExportStatus.Idle -> ""
            ExportStatus.Exporting -> getString(R.string.capability_export_running)
            is ExportStatus.Saved -> getString(R.string.capability_export_saved, export.path)
            is ExportStatus.Failed -> getString(R.string.capability_export_failed, export.reason)
        }
    }

    // FOOTER
    private fun bindListeners() {
        binding.exportButton.setOnClickListener { viewModel.onEvent(CapabilityEvent.ExportClicked) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun CapabilityCheck.toLine(): String {
        val verdict = getString(if (passed) R.string.capability_check_pass else R.string.capability_check_fail)
        return getString(R.string.capability_check_line, verdict, getString(id.labelRes()), detail)
    }
}

@StringRes
private fun CapabilityCheckId.labelRes(): Int = when (this) {
    CapabilityCheckId.GLES_31 -> R.string.capability_check_gles_31
    CapabilityCheckId.ESSL3_EXTERNAL_IMAGE -> R.string.capability_check_essl3_external_image
    CapabilityCheckId.COMPUTE_INVOCATIONS -> R.string.capability_check_compute_invocations
    CapabilityCheckId.COMPUTE_SHARED_MEMORY -> R.string.capability_check_compute_shared_memory
    CapabilityCheckId.THERMAL_API -> R.string.capability_check_thermal_api
    CapabilityCheckId.H264_HW_1080P60 -> R.string.capability_check_h264_hw_1080p60
    CapabilityCheckId.HEVC_HW_1080P60 -> R.string.capability_check_hevc_hw_1080p60
}
