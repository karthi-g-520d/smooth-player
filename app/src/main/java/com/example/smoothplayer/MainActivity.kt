package com.example.smoothplayer

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import com.example.smoothplayer.engine.impl.capability.DefaultCapabilityProbe
import com.example.smoothplayer.engine.impl.render.HelloGlRenderer
import com.example.smoothplayer.feature.player.capability.CapabilityFragment
import com.example.smoothplayer.feature.player.capability.CapabilityViewModel
import com.example.smoothplayer.feature.player.render.HelloGlFragment
import kotlinx.coroutines.Dispatchers

/** Wiring only: builds the dependencies and hosts the feature screens. */
class MainActivity : AppCompatActivity() {
    private val helloGlRenderer = HelloGlRenderer()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Set before super.onCreate so fragments restored after rotation are built with their dependencies.
        supportFragmentManager.fragmentFactory = screenFragmentFactory()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applyWindowInsets()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.hello_gl_container, HelloGlFragment::class.java, null)
                .replace(R.id.fragment_container, CapabilityFragment::class.java, null)
                .commit()
        }
    }

    override fun onDestroy() {
        helloGlRenderer.close()
        super.onDestroy()
    }

    private fun screenFragmentFactory(): FragmentFactory {
        val capabilityViewModelFactory = CapabilityViewModel.Factory(
            probe = DefaultCapabilityProbe(applicationContext),
            exporter = FileCapabilityExporter(applicationContext),
            ioDispatcher = Dispatchers.IO,
        )
        return object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment = when (className) {
                CapabilityFragment::class.java.name -> CapabilityFragment(capabilityViewModelFactory)
                HelloGlFragment::class.java.name -> HelloGlFragment(helloGlRenderer)
                else -> super.instantiate(classLoader, className)
            }
        }
    }

    private fun applyWindowInsets() {
        val padding = resources.getDimensionPixelSize(R.dimen.screen_padding)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
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
