package com.example.smoothplayer.engine.impl.interp

/**
 * The CPU search visits blocks in raster order, and each block's predictor reads its left, upper and upper-right
 * neighbours. On the GPU, blocks on the same wavefront `bx + 2 * by` have no such dependency on each other, and all of
 * their neighbours sit on earlier wavefronts. Dispatching wavefronts in order therefore gives the CPU's result.
 */
internal fun wavefrontCount(columns: Int, rows: Int): Int = (columns - 1) + 2 * (rows - 1) + 1

/** The first block row on [wave]. Blocks further left would need a column past the right edge. */
internal fun wavefrontFirstRow(wave: Int, columns: Int): Int =
    if (wave < columns) 0 else (wave - columns + 2) / 2

/** The last block row on [wave]. A row below it would need a column left of zero. */
internal fun wavefrontLastRow(wave: Int, rows: Int): Int = minOf(rows - 1, wave / 2)
