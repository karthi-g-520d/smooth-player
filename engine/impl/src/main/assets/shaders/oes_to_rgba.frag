#version 310 es
#extension GL_OES_EGL_image_external_essl3 : require
// Copies the decoder's external texture into an RGBA8 ring slot. The texture matrix is already applied by quad.vert.
precision mediump float;
layout(binding = 0) uniform samplerExternalOES u_videoTex;
in highp vec2 v_uv;
layout(location = 0) out vec4 o_color;
void main() {
    o_color = texture(u_videoTex, v_uv);
}
