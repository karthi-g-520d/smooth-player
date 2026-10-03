package com.example.smoothplayer.feature.player.render

import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import androidx.fragment.app.Fragment
import com.example.smoothplayer.engine.api.RenderTargetController
import com.example.smoothplayer.feature.player.R
import com.example.smoothplayer.feature.player.databinding.FragmentHelloGlBinding

/** Thin view: hosts the render surface and forwards its lifecycle to [renderer]. */
public class HelloGlFragment(
    private val renderer: RenderTargetController<Surface>,
) : Fragment(R.layout.fragment_hello_gl) {

    // HEADER
    private var _binding: FragmentHelloGlBinding? = null
    private val binding get() = checkNotNull(_binding)

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            renderer.onSurfaceChanged(holder.surface, width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            renderer.onSurfaceDestroyed()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHelloGlBinding.bind(view)
        binding.glSurface.holder.addCallback(surfaceCallback)
    }

    // FOOTER
    override fun onDestroyView() {
        _binding?.glSurface?.holder?.removeCallback(surfaceCallback)
        _binding = null
        super.onDestroyView()
    }
}
