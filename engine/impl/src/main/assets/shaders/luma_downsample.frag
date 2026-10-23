#version 310 es
// Fine motion luma. Each output texel is the mean of a D x D block of source texels, where D = 2 * u_taps is the
// motion-luma divisor (4 up to 720p, 6 at 1080p; see motionLumaDivisor in MotionLuma.kt). Each bilinear tap sits on
// the corner shared by a 2x2 group of source texels and averages them, and u_taps x u_taps taps two texels apart
// cover the block exactly.
// IN : u_srcTex (RGBA8 frame, binding 0), u_texel (1 / source size, location 6), u_taps (taps per axis, location 7),
//      v_uv
// OUT: o_luma (R8)
precision mediump float;
layout(binding = 0) uniform mediump sampler2D u_srcTex;
layout(location = 6) uniform highp vec2 u_texel;
layout(location = 7) uniform int u_taps;
in highp vec2 v_uv;
layout(location = 0) out mediump float o_luma;

const mediump vec3 LUMA_WEIGHTS = vec3(0.299, 0.587, 0.114);

void main() {
    // Tap i sits (2i + 1 - u_taps) source texels from the block centre: ±1 for 2 taps, -2, 0, 2 for 3.
    highp float sum = 0.0;
    for (int y = 0; y < u_taps; y++) {
        for (int x = 0; x < u_taps; x++) {
            highp vec2 offset = vec2(float(2 * x + 1 - u_taps), float(2 * y + 1 - u_taps));
            sum += dot(texture(u_srcTex, v_uv + offset * u_texel).rgb, LUMA_WEIGHTS);
        }
    }
    o_luma = sum / float(u_taps * u_taps);
}
