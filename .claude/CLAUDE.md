# SmoothPlayer — Android 30→60fps Frame Interpolation Video Player

Android app that plays local video files and generates extra frames in real time via custom GPU shaders (no paid SDKs, no neural nets in Phase 1) to convert 30fps playback to ~60fps. Classical pipeline: duplicate → blend → block-matching motion estimation → bidirectional warp → confidence-based fallback. Conceptually similar to DLSS Frame Generation, but for video decode/playback instead of game rendering.

## Current status

Phase 1 Steps 1–5 are done and accepted. Step 6 (motion estimation) is in progress. The plan records each step's measured results and accepted exceptions.

- **Done:** capability probe screen with JSON export (Step 1); MediaCodec → SurfaceTexture → GL player with audio-clock A/V sync, seek, and lifecycle tests (Step 2); debug overlay, GPU timers, Perfetto sections, telemetry CSV (Step 3); vsync-driven 60 Hz pacing, 98.9% cadence, accepted below the 99.5% bar (Step 4); 4-slot frame ring with 1/4 and 1/8 luma, Duplicate and Blend modes (Step 5).
- **Step 6 so far:** CPU reference block matcher (`engine/impl/.../interp/MotionSearch.kt`) and its GPU form (`GpuMotionSearch.kt`, `assets/shaders/motion_search.comp` + `motion_median.comp`), which must give the same field. The renderer runs it once per decoded frame on the 1/4 luma, and the overlay shows its GPU time. Still to do: on-device timing against the ≤ 3/5 ms bar, the MV visualization debug view, and the synthetic-pan clip check.
- **Not started:** Step 7 (MC warp, confidence, scene cuts, fallbacks), Step 8 (benchmarks, Go/No-Go).
- **Devices:** only the vivo V2334 (mid-range) so far. The plan also needs a flagship.

## Building and testing

- CI (`.github/workflows/ci.yml`) runs unit tests, lint and `assembleDebug` on every PR into `main`. It is the build check.
- Cloud sessions can't build the app: there's no Android SDK (dl.google.com is blocked), and Gradle's daemon wants JDK 25 from foojay, which is also blocked. Pure-Kotlin code and its tests can be compiled with a standalone `kotlinc`. Shaders can be checked with `glslangValidator`, and compute shaders can run on Mesa llvmpipe (GLES 3.1) through EGL surfaceless.
- Instrumented tests (`src/androidTest`) run only on a real device: `./gradlew :engine:impl:connectedDebugAndroidTest`.

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
