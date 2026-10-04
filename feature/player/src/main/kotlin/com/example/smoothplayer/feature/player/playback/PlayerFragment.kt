package com.example.smoothplayer.feature.player.playback

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.smoothplayer.engine.api.model.EngineError
import com.example.smoothplayer.engine.api.model.MICROS_PER_SECOND
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.api.model.PlaybackStats
import com.example.smoothplayer.engine.api.model.VideoInfo
import com.example.smoothplayer.feature.player.R
import com.example.smoothplayer.feature.player.databinding.FragmentPlayerBinding
import kotlinx.coroutines.launch

private const val VIDEO_MIME_FILTER = "video/*"
private const val MICROS_PER_MILLI = 1_000L
private const val NANOS_PER_MILLI = 1_000_000.0

/**
 * Thin view: launches the file picker, forwards clicks and surface events, and renders [PlayerUiState].
 *
 * See the ValidFragment note on [com.example.smoothplayer.feature.player.capability.CapabilityFragment]:
 * the same custom FragmentFactory covers this fragment too.
 */
@Suppress("ValidFragment")
public class PlayerFragment(
    private val viewModelFactory: ViewModelProvider.Factory,
) : Fragment(R.layout.fragment_player) {

    // HEADER
    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: PlayerViewModel by lazy {
        ViewModelProvider(this, viewModelFactory)[PlayerViewModel::class.java]
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onEvent(PlayerEvent.FileChosen(it.toString())) }
    }

    // True while the finger is on the seek bar. Position updates must not move the thumb under it.
    private var seekBarTracking = false

    // Surface events go to the view model's render target, not to a new one. See PlayerViewModel.
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            viewModel.renderTarget.onSurfaceChanged(holder.surface, width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            viewModel.renderTarget.onSurfaceDestroyed()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlayerBinding.bind(view)
        bindListeners()
        binding.videoSurface.holder.addCallback(surfaceCallback)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.effects.collect(::handleEffect) }
            }
        }
    }

    // BODY
    private fun render(state: PlayerUiState) = with(binding) {
        infoText.text = when (state.phase) {
            PlayerUiState.Phase.Idle -> getString(R.string.player_idle)
            PlayerUiState.Phase.Preparing -> getString(R.string.player_preparing)
            PlayerUiState.Phase.Ready, PlayerUiState.Phase.Playing, PlayerUiState.Phase.Paused,
            PlayerUiState.Phase.Ended -> state.videoInfo?.toLine().orEmpty()
            PlayerUiState.Phase.Failed -> getString(R.string.player_failed, state.error?.toMessage().orEmpty())
        }
        val hasVideo = state.phase == PlayerUiState.Phase.Ready ||
            state.phase == PlayerUiState.Phase.Playing ||
            state.phase == PlayerUiState.Phase.Paused ||
            state.phase == PlayerUiState.Phase.Ended
        playPauseButton.isEnabled = hasVideo
        playPauseButton.setText(
            if (state.phase == PlayerUiState.Phase.Playing) R.string.player_pause else R.string.player_play,
        )
        val durationMs = (state.videoInfo?.durationUs?.value ?: 0L) / MICROS_PER_MILLI
        seekBar.isEnabled = hasVideo
        seekBar.max = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        renderSeekPosition(state.position)
        renderStats(state)
    }

    private fun renderStats(state: PlayerUiState) = with(binding) {
        statsButton.setText(if (state.statsVisible) R.string.player_stats_hide else R.string.player_stats_show)
        logButton.setText(if (state.loggingEnabled) R.string.player_log_stop else R.string.player_log_start)
        statsText.visibility = if (state.statsVisible) View.VISIBLE else View.GONE
        statsText.text = state.stats?.toOverlayText() ?: getString(R.string.player_stats_waiting)
    }

    private fun PlaybackStats.toOverlayText(): String {
        val text = getString(
            R.string.player_stats,
            displayFps,
            contentFps,
            renderedFrames,
            droppedFrames,
            avDrift?.let { getString(R.string.player_stats_drift, it.value / MICROS_PER_MILLI) } ?: notAvailable(),
            presentGpuP50?.let { getString(R.string.player_stats_gpu, it.value / NANOS_PER_MILLI) } ?: notAvailable(),
            presentGpuP95?.let { getString(R.string.player_stats_gpu, it.value / NANOS_PER_MILLI) } ?: notAvailable(),
            refreshHz,
            thermalHeadroom?.let { getString(R.string.player_stats_thermal, it) } ?: notAvailable(),
        )
        return logPath?.let { "$text\n${getString(R.string.player_log_saved, it)}" } ?: text
    }

    private fun notAvailable(): String = getString(R.string.player_stats_na)

    private fun renderSeekPosition(position: Micros) {
        // Position updates must not move the thumb while the finger is on it.
        if (seekBarTracking) return
        binding.seekBar.progress = (position.value / MICROS_PER_MILLI).toInt()
    }

    // FOOTER
    private fun bindListeners() = with(binding) {
        chooseButton.setOnClickListener { viewModel.onEvent(PlayerEvent.ChooseFileClicked) }
        playPauseButton.setOnClickListener { viewModel.onEvent(PlayerEvent.PlayPauseClicked) }
        statsButton.setOnClickListener { viewModel.onEvent(PlayerEvent.StatsToggled) }
        logButton.setOnClickListener { viewModel.onEvent(PlayerEvent.LoggingToggled) }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(bar: SeekBar) {
                seekBarTracking = true
            }

            // Seek once on release, not on every drag step, so the decoder is not flushed dozens of times.
            override fun onStopTrackingTouch(bar: SeekBar) {
                seekBarTracking = false
                viewModel.onEvent(PlayerEvent.SeekRequested(Micros(bar.progress * MICROS_PER_MILLI)))
            }
        })
    }

    private fun handleEffect(effect: PlayerEffect) {
        when (effect) {
            PlayerEffect.OpenFilePicker -> openDocument.launch(arrayOf(VIDEO_MIME_FILTER))
        }
    }

    override fun onDestroyView() {
        _binding?.videoSurface?.holder?.removeCallback(surfaceCallback)
        _binding = null
        super.onDestroyView()
    }

    private fun VideoInfo.toLine(): String = getString(
        R.string.player_info,
        width,
        height,
        durationUs.value / MICROS_PER_SECOND,
        hasAudio,
        rotationDegrees,
    )

    private fun EngineError.toMessage(): String = when (this) {
        is EngineError.CodecFailed -> getString(R.string.player_error_codec, diagnostic)
        is EngineError.UnsupportedFormat -> getString(R.string.player_error_unsupported_format, mime)
        is EngineError.GlFailed -> getString(R.string.player_error_gl, glError.toString(16), where)
        is EngineError.SourceUnavailable -> getString(R.string.player_error_source_unavailable, reason)
        EngineError.ThermalThrottled -> getString(R.string.player_error_thermal)
    }
}
