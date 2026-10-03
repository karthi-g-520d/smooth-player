package com.example.smoothplayer.engine.api.model

public const val MICROS_PER_SECOND: Long = 1_000_000L
public const val NANOS_PER_MICRO: Long = 1_000L

/** Presentation timestamp or duration in microseconds. Never pass raw Long time values. */
@JvmInline
public value class Micros(public val value: Long) : Comparable<Micros> {
    public operator fun plus(other: Micros): Micros = Micros(value + other.value)
    public operator fun minus(other: Micros): Micros = Micros(value - other.value)
    override fun compareTo(other: Micros): Int = value.compareTo(other.value)
    public fun toNanos(): Nanos = Nanos(value * NANOS_PER_MICRO)
}

/** Monotonic time in nanoseconds (vsync and present timestamps). */
@JvmInline
public value class Nanos(public val value: Long) {
    public fun toMicros(): Micros = Micros(value / NANOS_PER_MICRO)
}

@JvmInline
public value class FrameIndex(public val value: Int)
