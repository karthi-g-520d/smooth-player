package com.example.smoothplayer.engine.api.model

/**
 * What replaces the motion-compensated warp for the whole picture in [InterpolationMode.MotionCompensated].
 *
 * [None] means the warp is in use. [LowConfidence] blends, because too many blocks found no good match. [Thermal] and
 * [GpuBudget] hold the nearer frame, as [InterpolationMode.Duplicate] does, because the phone is hot or the warp took
 * too long on the GPU.
 */
public enum class WarpFallback { None, LowConfidence, Thermal, GpuBudget }
