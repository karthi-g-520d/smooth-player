# SmoothPlayer — Android 30→60fps Frame Interpolation Video Player

Android app that plays local video files and generates extra frames in real time via custom GPU shaders (no paid SDKs, no neural nets in Phase 1) to convert 30fps playback to ~60fps. Classical pipeline: duplicate → blend → block-matching motion estimation → bidirectional warp → confidence-based fallback. Conceptually similar to DLSS Frame Generation, but for video decode/playback instead of game rendering.

## Current status

No code written yet. Next step is Phase 1 / Step 1: project setup, Hello GL on a dedicated thread, device capability probe. See the plan below.

## Required reading before writing code

- **Plan:** [.claude/plans/phase-1-plan.md](plans/phase-1-plan.md) — architecture, dependency versions, the 8-step gated build plan with acceptance criteria, test clip matrix, risk table, timeline. Follow the step order; each step has acceptance criteria that gate moving to the next.
- **Golden Rules skill:** `golden-rules` (`.claude/skills/golden-rules/SKILL.md`) — Kotlin/Android coding conventions for this project (module structure, MVI state, threading rules, MediaCodec/GLES/GLSL conventions, testing, git/docs). Invoke it (`/golden-rules` or via the Skill tool) before writing any Kotlin, Gradle, or shader code in this repo.

## Locked architectural decisions

- Platform: Android first (Kotlin), iOS later (Swift/Metal) — not in scope yet.
- UI: native Android Views now, Jetpack Compose later. Not React Native/Flutter/WebView — the render path must be native for frame pacing.
- Pipeline: `MediaExtractor` + `MediaCodec` (HW decode) → `SurfaceTexture` → `GL_TEXTURE_EXTERNAL_OES` → OpenGL ES 3.1 shaders (motion estimation + warp) → display-driven vsync presentation (`Choreographer` + `eglPresentationTimeANDROID`) → `AudioTrack` as the master clock.
- Engine/UI separation: `:engine:api` (pure Kotlin, no `android.*` imports) and `:engine:impl` (Android, all platform code, split into packages). `:feature:player` depends only on `:engine:api`.
- Scope: local non-DRM files only, 8-bit SDR H.264 first, real devices only (no emulator).
- Go/No-Go gates (end of Phase 1): ~10ms per generated frame at 1080p (720p mid-range), sustained 10+ min; ≥99% correct pacing cadence; visibly better than 30fps on pan/sports clips; acceptable thermal/battery.

Toolchain pins (recheck compatibility before bumping): AGP 9.4.x, Gradle 9.6+, Kotlin 2.4.20, compileSdk 37, minSdk 26.
