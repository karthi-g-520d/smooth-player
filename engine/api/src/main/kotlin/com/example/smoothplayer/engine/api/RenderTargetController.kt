package com.example.smoothplayer.engine.api

/**
 * Receives the lifecycle of a platform render surface from the UI.
 *
 * [S] is the platform surface type. The engine API stays free of `android.*` imports, so the app
 * chooses the concrete type when it wires an implementation to the UI.
 */
public interface RenderTargetController<in S : Any> {
    /** Called when a surface is created or resized. Also called when the same surface is reused. */
    public fun onSurfaceChanged(surface: S, width: Int, height: Int)

    /** Called when the surface is about to be destroyed. Nothing may draw to it after this returns. */
    public fun onSurfaceDestroyed()
}
