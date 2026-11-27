// Pan-clip check for Phase 1 Step 6: runs the CPU reference motion search (which the GPU search matches exactly) on
// decoded frames of a synthetic pan and reports the vector error against the known pan. See run.sh for how to build
// and run it, and the Step 6 notes in .claude/plans/phase-1-plan.md for the results.
//
// The luma is made the way the renderer makes it: Rec. 601 weights, the rounded mean of each divisor-sized block
// (luma_downsample.frag), then the rounded 2x2 mean for the coarse level (luma_half.frag). Rows are top-down here and
// bottom-up on the device, which only flips the sign of dy; the pans here are horizontal.
package com.example.smoothplayer.engine.impl.interp

import java.io.DataInputStream
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun lumaOf(rgb: ByteArray, w: Int, h: Int, d: Int): LumaPlane {
    val lw = (w + d - 1) / d; val lh = (h + d - 1) / d
    val px = IntArray(lw * lh)
    for (ly in 0 until lh) for (lx in 0 until lw) {
        var s = 0.0; var n = 0
        for (y in ly * d until minOf(ly * d + d, h)) for (x in lx * d until minOf(lx * d + d, w)) {
            val i = (y * w + x) * 3
            s += 0.299 * (rgb[i].toInt() and 255) + 0.587 * (rgb[i + 1].toInt() and 255) + 0.114 * (rgb[i + 2].toInt() and 255); n++
        }
        px[ly * lw + lx] = (s / n).roundToInt().coerceIn(0, 255)
    }
    return LumaPlane(lw, lh, px)
}

internal fun half(p: LumaPlane): LumaPlane {
    val w = (p.width + 1) / 2; val h = (p.height + 1) / 2
    return LumaPlane(w, h, IntArray(w * h) { i -> val x = 2 * (i % w); val y = 2 * (i / w)
        ((p.at(x, y) + p.at(x + 1, y) + p.at(x, y + 1) + p.at(x + 1, y + 1)) / 4.0).roundToInt() })
}

/**
 * Args: raw rgb24 file, width, height, frames, motion-luma divisor, true horizontal motion in source px per frame
 * (negative is leftward), and the crop's wrap period in frames (pairs across the wrap are skipped).
 */
fun main(args: Array<String>) {
    val w = args[1].toInt(); val h = args[2].toInt(); val frames = args[3].toInt(); val d = args[4].toInt()
    val trueDx = args[5].toDouble(); val wrap = args[6].toInt()
    val input = DataInputStream(FileInputStream(args[0]).buffered(1 shl 22))
    val buf = ByteArray(w * h * 3)
    var prev: LumaPlane? = null
    val errs = ArrayList<Double>(); val pairMedians = ArrayList<Double>()
    var within = 0
    for (f in 0 until frames) {
        input.readFully(buf)
        val cur = lumaOf(buf, w, h, d)
        val p = prev
        prev = cur
        if (p == null || f % wrap == 0) continue
        val field = estimateMotion(p, cur, half(p), half(cur))
        val pair = ArrayList<Double>()
        // Skip a one-block border: new content enters there.
        for (by in 1 until field.rows - 1) for (bx in 1 until field.columns - 1) {
            val i = by * field.columns + bx
            val vx = (field.dx[i] + field.subX[i]) * d
            val vy = (field.dy[i] + field.subY[i]) * d
            val e = Math.hypot(vx - trueDx, vy.toDouble())
            pair += e; errs += e
            if (e / 4.0 < 0.5) within++
        }
        pair.sort(); pairMedians += pair[pair.size / 2]
    }
    errs.sort()
    fun q(x: Double) = errs[((errs.size - 1) * x).toInt()]
    println("pairs=${pairMedians.size} blocks=${errs.size} divisor=$d trueDx=$trueDx")
    println("full-res px error: median=%.3f p90=%.3f p99=%.3f max=%.3f".format(q(0.5), q(0.9), q(0.99), errs.last()))
    println("1/4-res px error: median=%.3f p90=%.3f".format(q(0.5) / 4, q(0.9) / 4))
    println("worst pair median (full-res px)=%.3f; blocks under 0.5 px at 1/4 res: %.1f%%".format(pairMedians.max(), 100.0 * within / errs.size))
}
