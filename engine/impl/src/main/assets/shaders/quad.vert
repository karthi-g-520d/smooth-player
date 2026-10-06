#version 310 es
// Full-screen quad, shared by every pass. u_rotation turns the quad in clip space (the container's rotation metadata).
// u_texMatrix maps quad UVs into the sampled texture: the SurfaceTexture transform for the copy pass, identity for the
// rest. A mat2 uses two consecutive uniform locations, so u_texMatrix starts at location 2.
layout(location = 0) in vec2 a_position;
layout(location = 1) in vec2 a_uv;
layout(location = 0) uniform mat2 u_rotation;
layout(location = 2) uniform mat4 u_texMatrix;
out vec2 v_uv;
void main() {
    gl_Position = vec4(u_rotation * a_position, 0.0, 1.0);
    v_uv = (u_texMatrix * vec4(a_uv, 0.0, 1.0)).xy;
}
