#version 310 es
// Luma at 1/4 resolution. Each output texel is the mean of a 4x4 block of source texels.
// Four bilinear taps, one source texel out from the block centre, each average a 2x2 group of texels,
// so four taps cover the 4x4 block exactly.
// IN : u_srcTex (RGBA8 frame, binding 0), u_texel (1 / source size, location 6), v_uv
// OUT: o_luma (R8)
precision mediump float;
layout(binding = 0) uniform mediump sampler2D u_srcTex;
layout(location = 6) uniform highp vec2 u_texel;
in highp vec2 v_uv;
layout(location = 0) out mediump float o_luma;

const mediump vec3 LUMA_WEIGHTS = vec3(0.299, 0.587, 0.114);

mediump float lumaAt(highp vec2 uv) {
    return dot(texture(u_srcTex, uv).rgb, LUMA_WEIGHTS);
}

void main() {
    highp vec2 d = u_texel;
    o_luma = 0.25 * (
        lumaAt(v_uv + vec2(-d.x, -d.y)) +
        lumaAt(v_uv + vec2( d.x, -d.y)) +
        lumaAt(v_uv + vec2(-d.x,  d.y)) +
        lumaAt(v_uv + vec2( d.x,  d.y)));
}
