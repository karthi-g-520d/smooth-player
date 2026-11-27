#version 310 es
// Diagnostic views of the motion field, drawn in place of the picture. The video is shown in grey underneath, so the
// colours belong to the field alone.
//
// u_view 1, motion vectors: each block is tinted by its vector's direction (hue: right red, up green, left cyan,
// down violet), more strongly the faster it moved, and carries an arrow from its centre the way the content moved. The
// arrow's length grows with the square root of the speed and reaches nearly half a block at the search's reach, so slow
// motion still shows. A block that did not move has a dot and no tint.
// u_view 2, match error: each block is tinted from green (residual 0, a close match) through yellow to red (residual at
// or above u_maxResidual, a poor match).
// Both draw thin block borders, so the grid the vectors belong to is visible.
//
// Directions are in the picture's own frame, before any rotation for display: the arrows turn with the picture, the
// hues are named for the unrotated picture.
//
// IN : u_frame (RGBA8 ring slot, binding 0), u_motion (RGBA16F, one texel per block: x, y = vector in motion-luma
//      pixels, y up, z = residual; binding 1), u_frameSize (source pixels, location 6), u_lumaPx (source pixels per
//      motion-luma pixel, location 7), u_blockLuma (motion-luma pixels per block side, location 8), u_maxVector
//      (the search's reach in motion-luma pixels, location 9), u_maxResidual (location 10), u_view (location 11), v_uv
// OUT: o_color
precision mediump float;
layout(binding = 0) uniform mediump sampler2D u_frame;
layout(binding = 1) uniform highp sampler2D u_motion;
layout(location = 6) uniform highp vec2 u_frameSize;
layout(location = 7) uniform highp float u_lumaPx;
layout(location = 8) uniform highp float u_blockLuma;
layout(location = 9) uniform highp float u_maxVector;
layout(location = 10) uniform highp float u_maxResidual;
layout(location = 11) uniform int u_view;
in highp vec2 v_uv;
layout(location = 0) out vec4 o_color;

const mediump vec3 LUMA_WEIGHTS = vec3(0.299, 0.587, 0.114);
const highp float TWO_PI = 6.28318530718;
const mediump float GREY_LEVEL = 0.6;
const mediump float BORDER_MIX = 0.5;
const mediump float MAX_TINT = 0.7;
// Below this speed, in motion-luma pixels, a block counts as still: no tint, and a dot instead of an arrow.
const highp float STILL_SPEED = 0.1;
const highp float ARROW_REACH = 0.45;      // longest arrow, in block sides
const highp float ARROW_HALF_WIDTH = 0.03; // in block sides
const highp float MIN_ARROW = 0.25;        // shortest arrow of a moving block, as a fraction of ARROW_REACH
const highp float HEAD_SPREAD = 0.6;       // half the arrow head's width, as a fraction of HEAD_LENGTH
const mediump float MIN_TINT = 0.3;        // tint of the slowest moving block, as a fraction of MAX_TINT
const mediump float HEAT_TINT = 0.5;
const highp float HEAD_LENGTH = 0.15;      // in block sides
const highp float DOT_RADIUS = 0.06;       // in block sides
const highp float BORDER = 0.03;           // in block sides
const mediump vec3 ARROW_COLOR = vec3(1.0);
const mediump vec3 BORDER_COLOR = vec3(0.0);

mediump vec3 hueToRgb(highp float hue) {
    highp vec3 k = mod(vec3(5.0, 3.0, 1.0) + hue * 6.0, 6.0);
    return 1.0 - clamp(min(k, 4.0 - k), 0.0, 1.0);
}

// Distance from p to the segment a-b.
highp float segmentDistance(highp vec2 p, highp vec2 a, highp vec2 b) {
    highp vec2 ab = b - a;
    highp float len2 = dot(ab, ab);
    highp float h = len2 > 0.0 ? clamp(dot(p - a, ab) / len2, 0.0, 1.0) : 0.0;
    return length(p - a - ab * h);
}

void main() {
    highp vec2 lumaPos = v_uv * u_frameSize / u_lumaPx;
    highp vec2 blockPos = lumaPos / u_blockLuma;
    ivec2 grid = textureSize(u_motion, 0);
    ivec2 block = clamp(ivec2(floor(blockPos)), ivec2(0), grid - 1);
    highp vec4 field = texelFetch(u_motion, block, 0);
    // Position inside the block, in block sides, with the centre at the origin.
    highp vec2 local = fract(blockPos) - 0.5;

    mediump float grey = dot(texture(u_frame, v_uv).rgb, LUMA_WEIGHTS) * GREY_LEVEL;
    mediump vec3 color = vec3(grey);

    if (u_view == 1) {
        highp vec2 v = field.xy;
        highp float speed = length(v);
        highp float strength = sqrt(clamp(speed / u_maxVector, 0.0, 1.0));
        if (speed >= STILL_SPEED) {
            highp float hue = fract(atan(v.y, v.x) / TWO_PI + 1.0);
            color = mix(color, hueToRgb(hue), MAX_TINT * max(strength, MIN_TINT));
            highp vec2 dir = v / speed;
            highp vec2 tip = dir * ARROW_REACH * max(strength, MIN_ARROW);
            highp float shaft = segmentDistance(local, vec2(0.0), tip);
            highp vec2 side = vec2(-dir.y, dir.x) * HEAD_LENGTH * HEAD_SPREAD;
            highp vec2 headBase = tip - dir * HEAD_LENGTH;
            highp float head = min(segmentDistance(local, tip, headBase + side),
                                   segmentDistance(local, tip, headBase - side));
            if (min(shaft, head) < ARROW_HALF_WIDTH) color = ARROW_COLOR;
        } else if (length(local) < DOT_RADIUS) {
            color = ARROW_COLOR;
        }
    } else if (u_view == 2) {
        highp float bad = clamp(field.z / u_maxResidual, 0.0, 1.0);
        mediump vec3 heat = bad < 0.5 ? mix(vec3(0.0, 1.0, 0.0), vec3(1.0, 1.0, 0.0), bad * 2.0)
                                      : mix(vec3(1.0, 1.0, 0.0), vec3(1.0, 0.0, 0.0), bad * 2.0 - 1.0);
        color = mix(color, heat, HEAT_TINT);
    }

    if (any(greaterThan(abs(local), vec2(0.5 - BORDER)))) color = mix(color, BORDER_COLOR, BORDER_MIX);
    o_color = vec4(color, 1.0);
}
