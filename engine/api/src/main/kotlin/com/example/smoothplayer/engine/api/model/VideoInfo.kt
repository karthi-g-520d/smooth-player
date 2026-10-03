package com.example.smoothplayer.engine.api.model

/** Track info read from the container. [rotationDegrees] is the clockwise display rotation (0/90/180/270). */
public data class VideoInfo(
    public val durationUs: Micros,
    public val width: Int,
    public val height: Int,
    public val rotationDegrees: Int,
    public val hasAudio: Boolean,
)
