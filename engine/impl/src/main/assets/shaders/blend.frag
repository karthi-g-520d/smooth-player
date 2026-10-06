#version 310 es
// Shows the frame at phase u_t between frame A (binding 0) and frame B (binding 1). Duplicate mode passes t = 0,
// and when no B is resident A is bound to both units, so the same shader serves both cases.
// IN : u_frameA, u_frameB (RGBA8 ring slots), u_t (location 6), u_debugView (location 7), v_uv
// OUT: o_color
// DEBUG_VIEW: 0 = blend, 1 = |A - B| amplified (where the two frames differ), 2 = t as grey (checks u_t arrives)
precision mediump float;
layout(binding = 0) uniform mediump sampler2D u_frameA;
layout(binding = 1) uniform mediump sampler2D u_frameB;
layout(location = 6) uniform highp float u_t;
layout(location = 7) uniform int u_debugView;
in highp vec2 v_uv;
layout(location = 0) out vec4 o_color;

void main() {
    vec4 a = texture(u_frameA, v_uv);
    vec4 b = texture(u_frameB, v_uv);
    vec4 color = mix(a, b, u_t);
    if (u_debugView == 1) {
        color = vec4(clamp(abs(a - b).rgb * 8.0, 0.0, 1.0), 1.0);
    } else if (u_debugView == 2) {
        color = vec4(vec3(u_t), 1.0);
    }
    o_color = color;
}
