#version 310 es
// Motion-compensated in-between frame at phase u_t between frame A (binding 0) and frame B (binding 1), from the pair's
// motion field (binding 2). The field holds, per block of B, the motion v from A to B (B(p) ~ A(p - v)) in motion-luma
// pixels. It is sampled bilinearly, so the vectors change smoothly from block to block.
//
// For the output pixel p, with v the field at p:
//   cA = A(p - t v), cB = B(p + (1 - t) v), warped = ((1 - t) cA + t cB) / weights
// and the plain blend mix(A(p), B(p), t) is the fallback. They are mixed by a confidence that is the product of:
//   - the block's match: 1 for a residual at or under RESIDUAL_GOOD, 0 at or over RESIDUAL_BAD. Flat, dark areas have
//     little detail, so their residual is high and they blend, where the blend looks the same anyway;
//   - the two warped samples agreeing: where cA and cB differ, the pixel is covered or uncovered between the frames,
//     or the vector is wrong, so it blends instead of tearing;
//   - u_weight, the whole-picture switch the renderer sets (0 for the low-confidence fallback, which blends).
// A pair the stats pass judged a scene cut (u_verdict 0) shows frame A unchanged, since blending two shots is worse
// than holding one. The verdict comes from the GPU, so the warp never waits for the CPU to read the stats.
// A warped sample that lands outside its frame is dropped and the other side is used alone, so content entering at an
// edge is not smeared from the clamped border.
//
// IN : u_frameA, u_frameB (RGBA8 ring slots), u_field (RGBA16F, x, y = vector in motion-luma pixels, z = residual),
//      u_verdict (binding 3, 1x1 R32F: 1 = interpolate, 0 = scene cut),
//      u_t (location 6), u_lumaToUv (one motion-luma pixel in frame UV, location 7), u_fieldScale (frame UV to field
//      UV, location 8), u_weight (location 9), u_debugView (location 10), v_uv
// OUT: o_color
// DEBUG VIEW: 0 = picture, 1 = confidence (green = warped, red = blended), over the picture in grey
precision mediump float;
layout(binding = 0) uniform mediump sampler2D u_frameA;
layout(binding = 1) uniform mediump sampler2D u_frameB;
layout(binding = 2) uniform highp sampler2D u_field;
layout(binding = 3) uniform highp sampler2D u_verdict;
layout(location = 6) uniform highp float u_t;
layout(location = 7) uniform highp vec2 u_lumaToUv;
layout(location = 8) uniform highp vec2 u_fieldScale;
layout(location = 9) uniform mediump float u_weight;
layout(location = 10) uniform int u_debugView;
in highp vec2 v_uv;
layout(location = 0) out vec4 o_color;

const highp float RESIDUAL_GOOD = 0.35;
const highp float RESIDUAL_BAD = 0.8;
// Largest channel difference between the two warped samples, in 0..1 colour.
const mediump float DISAGREE_LOW = 0.06;
const mediump float DISAGREE_HIGH = 0.2;
const mediump vec3 LUMA_WEIGHTS = vec3(0.299, 0.587, 0.114);
const mediump float DEBUG_GREY = 0.5;
const mediump float DEBUG_TINT = 0.6;

bool inFrame(highp vec2 uv) {
    return all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));
}

void main() {
    highp vec4 field = texture(u_field, v_uv * u_fieldScale);
    highp vec2 shift = field.xy * u_lumaToUv;
    highp vec2 uvA = v_uv - u_t * shift;
    highp vec2 uvB = v_uv + (1.0 - u_t) * shift;

    vec4 fallback = mix(texture(u_frameA, v_uv), texture(u_frameB, v_uv), u_t);
    vec4 cA = texture(u_frameA, uvA);
    vec4 cB = texture(u_frameB, uvB);

    bool aIn = inFrame(uvA);
    bool bIn = inFrame(uvB);
    mediump float wA = aIn ? 1.0 - u_t : 0.0;
    mediump float wB = bIn ? u_t : 0.0;
    mediump float wSum = wA + wB;
    vec4 warped = wSum > 0.0 ? (wA * cA + wB * cB) / wSum : fallback;

    mediump float blockConfidence = 1.0 - smoothstep(RESIDUAL_GOOD, RESIDUAL_BAD, field.z);
    vec3 difference = abs(cA.rgb - cB.rgb);
    mediump float disagree = max(difference.r, max(difference.g, difference.b));
    // With one side outside the frame there is nothing to compare against, so only the block's match counts.
    mediump float agreement = (aIn && bIn) ? 1.0 - smoothstep(DISAGREE_LOW, DISAGREE_HIGH, disagree) : 1.0;
    mediump float confidence = wSum > 0.0 ? blockConfidence * agreement * u_weight : 0.0;

    bool sceneCut = texelFetch(u_verdict, ivec2(0), 0).r < 0.5;
    if (sceneCut) confidence = 0.0;
    vec4 color = sceneCut ? texture(u_frameA, v_uv) : mix(fallback, warped, confidence);
    if (u_debugView == 1) {
        mediump vec3 grey = vec3(dot(color.rgb, LUMA_WEIGHTS) * DEBUG_GREY);
        color = vec4(mix(grey, vec3(1.0 - confidence, confidence, 0.0), DEBUG_TINT), 1.0);
    }
    o_color = color;
}
