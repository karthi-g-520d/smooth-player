package com.example.smoothplayer.engine.api.model

/**
 * What the screen shows in place of the normal picture, for checking the interpolation pipeline.
 *
 * [MotionVectors] shows the motion field of the frame pair on screen (or the newest searched pair) over a grey copy of
 * the video: one arrow per block pointing the way the content moved, coloured by direction, brighter the faster it
 * moved. [MatchError] colours each block by how well its best match fits, green for a close match and red for a poor
 * one, so the blocks a warp should not trust stand out. [WarpConfidence] shows, per pixel, how much of the picture the
 * motion-compensated warp draws: green where it is warped, red where it falls back to the blend or a held frame. All
 * need motion estimation; without it the normal picture shows.
 */
public enum class DebugView { Off, MotionVectors, MatchError, WarpConfidence }
