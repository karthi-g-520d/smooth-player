package com.example.smoothplayer.engine.api.model

/**
 * How the frame for each refresh is chosen.
 *
 * [Duplicate] shows the newest decoded frame on every refresh. [Blend] mixes the two decoded frames either side of the
 * content time the refresh will show, so the picture moves between frames rather than stepping. [MotionCompensated]
 * builds the in-between frame by moving both frames along the estimated motion, and falls back to the blend wherever
 * the motion is not trusted, and to holding a frame across scene cuts.
 */
public enum class InterpolationMode { Duplicate, Blend, MotionCompensated }
