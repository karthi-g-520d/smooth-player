#version 310 es
// Coarse motion luma, at half the fine level. One bilinear tap at the centre of a 2x2 block averages those four texels.
// IN : u_srcTex (R8 fine motion luma, binding 0), v_uv
// OUT: o_luma (R8)
precision mediump float;
layout(binding = 0) uniform mediump sampler2D u_srcTex;
in highp vec2 v_uv;
layout(location = 0) out mediump float o_luma;

void main() {
    o_luma = texture(u_srcTex, v_uv).r;
}
