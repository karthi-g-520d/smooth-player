package com.example.smoothplayer

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import com.example.smoothplayer.engine.impl.capability.DefaultCapabilityProbe
import com.example.smoothplayer.feature.player.capability.CapabilityFragment
import com.example.smoothplayer.feature.player.capability.CapabilityViewModel
import kotlinx.coroutines.Dispatchers

/** Wiring only: builds the dependencies and hosts the feature screen. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Set before super.onCreate so fragments restored after rotation are built with their dependencies.
        supportFragmentManager.fragmentFactory = capabilityFragmentFactory()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applyWindowInsets()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, CapabilityFragment::class.java, null)
                .commit()
        }
    }

    private fun capabilityFragmentFactory(): FragmentFactory {
        val viewModelFactory = CapabilityViewModel.Factory(
            probe = DefaultCapabilityProbe(applicationContext),
            exporter = FileCapabilityExporter(applicationContext),
            ioDispatcher = Dispatchers.IO,
        )
        return object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
                if (className == CapabilityFragment::class.java.name) {
                    CapabilityFragment(viewModelFactory)
                } else {
                    super.instantiate(classLoader, className)
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
