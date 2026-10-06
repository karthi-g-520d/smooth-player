package com.example.smoothplayer

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import com.example.smoothplayer.engine.impl.capability.DefaultCapabilityProbe
import com.example.smoothplayer.engine.impl.playback.DefaultPlaybackEngine
import com.example.smoothplayer.feature.player.capability.CapabilityFragment
import com.example.smoothplayer.feature.player.capability.CapabilityViewModel
import com.example.smoothplayer.feature.player.playback.PlayerFragment
import com.example.smoothplayer.feature.player.playback.PlayerViewModel
import kotlinx.coroutines.Dispatchers

/** Wiring only: builds the dependencies and hosts the feature screens. */
class MainActivity : AppCompatActivity() {
    // While the player is full screen, the capability pane is hidden and the system bars are hidden too.
    private var playerFullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Set before super.onCreate so fragments restored after rotation are built with their dependencies.
        supportFragmentManager.fragmentFactory = screenFragmentFactory()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applyWindowInsets()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.player_container, PlayerFragment::class.java, null)
                .replace(R.id.fragment_container, CapabilityFragment::class.java, null)
                .commit()
        }
    }

    private fun screenFragmentFactory(): FragmentFactory {
        val capabilityViewModelFactory = CapabilityViewModel.Factory(
            probe = DefaultCapabilityProbe(applicationContext),
            exporter = FileCapabilityExporter(applicationContext),
            ioDispatcher = Dispatchers.IO,
        )
        // One engine is both the playback controller and the surface target, so the fragment's surface
        // events reach the same engine the view model drives.
        val playbackEngine = DefaultPlaybackEngine(applicationContext, Dispatchers.IO)
        val playerViewModelFactory = PlayerViewModel.Factory(
            engine = playbackEngine,
            renderTarget = playbackEngine,
        )
        return object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment = when (className) {
                CapabilityFragment::class.java.name -> CapabilityFragment(capabilityViewModelFactory)
                PlayerFragment::class.java.name -> PlayerFragment(playerViewModelFactory, this@MainActivity::setPlayerFullscreen)
                else -> super.instantiate(classLoader, className)
            }
        }
    }

    private fun setPlayerFullscreen(fullscreen: Boolean) {
        playerFullscreen = fullscreen
        findViewById<View>(R.id.fragment_container).visibility = if (fullscreen) View.GONE else View.VISIBLE
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            // Bars come back as a transient overlay on a swipe, so they never stay over the picture.
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        // The insets listener only runs when insets change, so ask for it now to drop or restore the padding.
        ViewCompat.requestApplyInsets(findViewById(R.id.main))
    }

    private fun applyWindowInsets() {
        val screenPadding = resources.getDimensionPixelSize(R.dimen.screen_padding)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val padding = if (playerFullscreen) 0 else screenPadding
            view.setPadding(
                bars.left + padding,
                bars.top + padding,
                bars.right + padding,
                bars.bottom + padding,
            )
            insets
        }
    }
}
