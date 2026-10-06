package com.example.smoothplayer.feature.player.playback

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.smoothplayer.engine.api.model.EngineError
import com.example.smoothplayer.engine.api.model.InterpolationMode
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
 * [onFullscreenChanged] lets the host screen hide its own panes and system bars. The player cannot do that from
 * inside its own view, because the host owns the layout around it.
 *
 * See the ValidFragment note on [com.example.smoothplayer.feature.player.capability.CapabilityFragment]:
 * the same custom FragmentFactory covers this fragment too.
 */
@Suppress("ValidFragment")
public class PlayerFragment(
    private val viewModelFactory: ViewModelProvider.Factory,
    private val onFullscreenChanged: (Boolean) -> Unit,
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

    // Back leaves full screen rather than leaving the screen. Enabled only while full screen.
    private val exitFullscreenOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            viewModel.onEvent(PlayerEvent.FullscreenToggled)
        }
    }

    // The full-screen layout last applied to the views. Render runs on every position tick, so the layout is only
    // changed when this value changes. Null means the views have not been laid out for either state yet.
    private var appliedFullscreen: Boolean? = null

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
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, exitFullscreenOnBack)
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
        modeButton.setText(
            if (state.interpolation == InterpolationMode.Blend) {
                R.string.player_mode_blend
            } else {
                R.string.player_mode_duplicate
            },
        )
        renderFullscreen(state.fullscreen)
    }

    private fun renderStats(state: PlayerUiState) = with(binding) {
        statsButton.setText(if (state.statsVisible) R.string.player_stats_hide else R.string.player_stats_show)
        logButton.setText(if (state.loggingEnabled) R.string.player_log_stop else R.string.player_log_start)
        statsText.visibility = if (state.statsVisible) View.VISIBLE else View.GONE
        statsText.text = state.stats?.toOverlayText() ?: getString(R.string.player_stats_waiting)
    }

    /**
     * Full screen keeps the picture and the transport (seek, play, mode) and hides the file button, the info line
     * and the stats row. The picture then takes the height that is left.
     */
    private fun renderFullscreen(fullscreen: Boolean) {
        if (fullscreen == appliedFullscreen) return
        appliedFullscreen = fullscreen
        onFullscreenChanged(fullscreen)
        exitFullscreenOnBack.isEnabled = fullscreen
        with(binding) {
            val spacing = resources.getDimensionPixelSize(R.dimen.dimen_player_spacing)
            val videoHeight = resources.getDimensionPixelSize(R.dimen.dimen_player_video_height)
            playerContent.layoutParams = playerContent.layoutParams.apply {
                height = if (fullscreen) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            val padding = if (fullscreen) 0 else spacing
            playerContent.setPadding(padding, padding, padding, padding)
            videoContainer.layoutParams = (videoContainer.layoutParams as LinearLayout.LayoutParams).apply {
                if (fullscreen) {
                    height = 0
                    weight = 1f
                    topMargin = 0
                } else {
                    height = videoHeight
                    weight = 0f
                    topMargin = spacing
                }
            }
            chooseButton.setShown(!fullscreen)
            infoText.setShown(!fullscreen)
            controlsRow.setShown(!fullscreen)
            fullscreenButton.setText(
                if (fullscreen) R.string.player_fullscreen_exit else R.string.player_fullscreen_enter,
            )
        }
    }

    private fun View.setShown(shown: Boolean) {
        visibility = if (shown) View.VISIBLE else View.GONE
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
            cadenceText(),
        )
        return logPath?.let { "$text\n${getString(R.string.player_log_saved, it)}" } ?: text
    }

    /** The current one-second cadence, with the whole-playback average beside it once there is one. */
    private fun PlaybackStats.cadenceText(): String {
        val current = cadenceExactPercent?.let { getString(R.string.player_stats_percent, it) } ?: notAvailable()
        val average = cadenceAverageExactPercent ?: return current
        // One decimal, because the acceptance bar is 99.5% and whole percents cannot show whether it is met.
        return getString(R.string.player_stats_cadence, current, getString(R.string.player_stats_percent_precise, average))
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
        modeButton.setOnClickListener { viewModel.onEvent(PlayerEvent.InterpolationToggled) }
        fullscreenButton.setOnClickListener { viewModel.onEvent(PlayerEvent.FullscreenToggled) }
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
        appliedFullscreen = null
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
