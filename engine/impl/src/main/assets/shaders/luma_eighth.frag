#version 310 es
// Luma at 1/8 resolution from the 1/4 level. One bilinear tap at the centre of a 2x2 block averages those four texels.
// IN : u_srcTex (R8 luma at 1/4, binding 0), v_uv
// OUT: o_luma (R8)
precision mediump float;
layout(binding = 0) uniform mediump sampler2D u_srcTex;
in highp vec2 v_uv;
layout(location = 0) out mediump float o_luma;

void main() {
    o_luma = texture(u_srcTex, v_uv).r;
}
