# Real-Time 30→60 fps Frame Interpolation Video Player for Android — Phase 1 Plan Document (October 2026)

**Bottom line:** Build Phase 1 directly on the Android platform APIs: MediaExtractor and MediaCodec feeding a SurfaceTexture, then your own OpenGL ES 3.1 shaders, with AudioTrack as the master clock. The only libraries you need are free Apache-2.0 AndroidX and Kotlin artifacts. Media3/ExoPlayer is optional and should not carry the interpolation path, because its effects pipeline is designed around transforming frames, not inventing new timestamped frames. Work in eight gated steps. Get pacing right with duplicated frames first, then blending, then block-matching motion vectors, then bidirectional warping. Make the Go/No-Go call on real devices after roughly 11–13 weeks.

## TL;DR
- **Stack (all free):** Android Studio Rabbit 1 (2026.2.1), AGP 9.4.x, Gradle 9.6.0+, Kotlin 2.4.20, compileSdk 37, minSdk 26, core 1.19.1, appcompat 1.8.0, activity 1.13.0, lifecycle 2.11.0, kotlinx-coroutines-android 1.11.0. Media3 1.11.1 and NDK/ncnn are optional and come later. Every core dependency is Apache 2.0.
- **Architecture:** Each module (decoder, GL interpolator, display-driven pacer, audio clock, debug overlay) owns its own thread and none depends on the UI. The pacer works backwards from each vsync: it computes the content time to show, picks the two decoded frames on either side of it (frame A and frame B), and asks the interpolator for the in-between frame at a fraction t between 0 and 1. That one design handles 30→60, 24→60 and frame drops.
- **Phase 1 order:** (1) project setup and capability probe, (2) MediaCodec player with A/V sync, (3) instrumentation, (4) 60 Hz duplication with correct pacing, (5) two-frame ring buffer plus blending, (6) motion vectors from block matching on 1/4-res luma, (7) bidirectional warp with confidence checks, scene-cut detection and fallbacks, (8) benchmarking and the Go/No-Go decision. The budget is about 10 ms per generated frame, sustained for 10+ minutes.

---

## 1. Overview, Objective and Scope

**Objective.** Ship an Android spike app that plays local video files and turns 30 fps content into a smooth 60 Hz presentation. It does this in real time with frames it generates itself: its own GPU shaders do the motion estimation and the frame warping, with no paid SDKs and no neural networks in Phase 1. The spike exists to answer one question: is classical GPU interpolation on a phone fast enough, good-looking enough and cool-running enough to build a product on?

**In scope (Phase 1)**
- Local, non-DRM files chosen through the system file picker. Start with 8-bit SDR H.264 in MP4, then add HEVC 8-bit once that works.
- Hardware decode through MediaCodec, GPU rendering through OpenGL ES 3.1 with EGL14/GLES31, and presentation at 60 Hz.
- Audio playback through AudioTrack, used as the master clock, with A/V sync driven by presentation timestamps (PTS).
- Classical interpolation: duplicate → blend → block-matching motion estimation (ME) → bidirectional warp → confidence-based fallbacks. Every interpolated frame is requested at an arbitrary t ∈ [0,1].
- Debug overlay, GPU timers, a motion-vector visualization mode, and thermal/battery logging.
- Testing on real devices only: one mid-range device (Mali-G57/G68 or Adreno 6xx class) and one flagship.

**Out of scope (Phase 1)**
- DRM/Widevine content, streaming (HLS/DASH), HDR/10-bit, Dolby Vision, AV1 10-bit.
- Neural interpolation (RIFE), dense optical flow, Compose UI, iOS.
- Emulator support. Emulators don't reflect real GPU, decoder or vsync behaviour.
- Subtitles, playlists, background playback, Picture-in-Picture.

---

## 2. Architecture

### 2.1 Modules (all independent of the UI)

| Module | Responsibility | Key APIs |
|---|---|---|
| `capability` | Probes GL version, extensions, compute limits, decoders and display modes at startup | `EGL14`, `GLES31.glGetIntegerv`, `MediaCodecList`, `Display.getSupportedModes()` |
| `demux` | Splits the container into tracks and reads samples with their PTS | `MediaExtractor` |
| `decoder` | Video decode to a Surface, audio decode to PCM | `MediaCodec` (async callback mode) |
| `gl` | EGL context, the external OES texture, the RGBA ring buffer, shader programs | `EGL14`, `EGLExt`, `GLES31`, `SurfaceTexture` |
| `interp` | Builds the luma pyramid, runs ME, filters vectors, warps and blends, scores confidence, detects scene cuts | GLES 3.1 compute and fragment shaders |
| `pacer` | Vsync loop, picks the target content time, picks frames A/B and t, sets presentation time | `Choreographer`, `eglPresentationTimeANDROID`, `Surface.setFrameRate` |
| `audio` | AudioTrack output and the master clock | `AudioTrack`, `AudioTrack.getTimestamp()` |
| `telemetry` | Per-stage GPU/CPU ms, dropped frames, thermal data, CSV export | `EXT_disjoint_timer_query`, `PowerManager.getThermalHeadroom()` |
| `ui` (thin) | Activity, `SurfaceView`, controls, overlay toggles | Android Views (Compose later) |

### 2.2 Data flow, from file to screen

```
File (SAF Uri) ─► MediaExtractor ─┬─► video samples ─► MediaCodec (HW) ─► Surface(SurfaceTexture)
                                  │                                          │ onFrameAvailable
                                  │                                          ▼
                                  │                            GL thread: updateTexImage()
                                  │                            OES → RGBA copy (apply transform matrix)
                                  │                            → RingBuffer[slot] + luma pyramid (¼, ⅛ …)
                                  │                                          │
                                  │                   Pacer (per vsync): content time c = audioClock(now+latency)
                                  │                   choose A,B with ptsA ≤ c < ptsB, t=(c-ptsA)/(ptsB-ptsA)
                                  │                                          ▼
                                  │                    Interpolator(A,B,t) → ME (cached per A/B pair) → warp+blend
                                  │                                          ▼
                                  │                    draw to window EGLSurface, eglPresentationTimeANDROID, swap
                                  └─► audio samples ─► MediaCodec (audio) ─► PCM ─► AudioTrack (master clock)
```

### 2.3 Threading model

| Thread | Owns | Notes |
|---|---|---|
| **Main/UI** | Activity, SurfaceView callbacks, controls | Never touches GL or codec buffers. Sends commands (play, pause, seek) to an engine coroutine scope or HandlerThread. |
| **Extractor/Decoder** | `MediaExtractor`, video `MediaCodec` (callback on its own `HandlerThread`) | Feeds input buffers. Releases output buffers to the Surface **one at a time**, and only after the GL thread has consumed the previous one (see §2.4). |
| **GL/Render** | EGL context, SurfaceTexture, every shader, the pacer's Choreographer callback | One thread owns the context. Use a `HandlerThread` with its own Looper so `Choreographer.getInstance()` and `SurfaceTexture.setOnFrameAvailableListener(listener, handler)` both run here. |
| **Audio** | Audio `MediaCodec` plus a blocking `AudioTrack.write()` loop | Publishes the clock: `(framePosition, nanoTime)` from `getTimestamp()`, converted to a media timestamp in µs. |
| **Telemetry (optional)** | Thermal polling every few seconds, CSV writer | Low priority. |

Use `kotlinx.coroutines` only for control flow and lifecycle (start, stop, seek orchestration). Keep the hot paths (codec callbacks, the GL loop) on dedicated Looper threads, so the coroutine dispatcher never adds jitter.

### 2.4 Frame queue: keeping frames N and N+1 resident

- **A SurfaceTexture only keeps the latest frame.** The Android Developers SurfaceTexture reference says `updateTexImage()` updates the texture "to contain the most recent image from the image stream. This may cause some frames of the stream to be skipped." So if the decoder calls `releaseOutputBuffer(idx, true)` faster than the GL thread calls `updateTexImage()`, frames are lost. The rule: render one output buffer, wait for `onFrameAvailable`, call `updateTexImage()`, **copy it out**, and only then release the next one. Grafika's decoder examples throttle the same way.
- **Copy each OES frame into a regular texture.** A fragment pass samples `samplerExternalOES` and writes into a ring of K = 3–4 `GL_RGBA8` textures (frames A, B and C prefetched, plus one spare). The same pass, or the next one, writes a `GL_R8` or `GL_R16F` luma texture at 1/4 resolution for ME. Store each slot's PTS beside it.
- **Why 3–4 slots and not 2:** frame B must already be decoded when the first interpolated frame between A and B is due. One extra slot absorbs decoder jitter.

### 2.5 How generated frames get timestamps (display-driven model)

Don't think of the job as "insert a frame between every pair". Think of it as **the display asking for whatever the content looks like at a given moment.**

1. On each vsync callback, estimate when this frame will actually reach the screen: `T_present`. On API 33+ that is the `Choreographer.FrameData` expected presentation time. Otherwise use `frameTimeNanos + N × vsyncPeriod`, where N is the pipeline depth, usually 2.
2. Map it to content time: `c = audioMediaTimeAt(T_present)`, derived from `AudioTrack.getTimestamp()` plus the output latency.
3. Find the ring-buffer pair with `ptsA ≤ c < ptsB`. Set `t = (c − ptsA)/(ptsB − ptsA)`.
4. If t < ε, show A. If t > 1−ε, show B. Otherwise run `interp(A, B, t)`.
5. Call `eglPresentationTimeANDROID(display, surface, T_present)` and then `eglSwapBuffers`.

With 30 fps content on a 60 Hz display, this naturally produces t ≈ 0, 0.5, 0, 0.5, … With 24 fps on 60 Hz it produces t = 0, 0.4, 0.8, 0.2, 0.6, …, which removes 3:2 judder with no special-case code. If the decoder falls behind (B not ready), show A again and count a "late". If c moves past several frames, drop them. The audio clock always wins.

---

## 3. Dependencies and Packages (checked October 2026)

### 3.1 Toolchain

| Item | Version | Notes / source |
|---|---|---|
| Android Studio | **Rabbit 1 (2026.2.1)**, stable since 1 Oct 2026 | Google's Android Studio Release Updates blog announced on 1 Oct 2026 that "Android Studio Rabbit 1 is now available in the Stable channel". The compatibility table at developer.android.com/studio/releases lists "Rabbit 1 \| 2026.2.1 \| 7.1-9.4", i.e. AGP 7.1–9.4. Quail 4 Patch 1 is the previous stable.\[1\] |
| Android Gradle Plugin | **9.4.0** (Sept 2026).\[2\] The API reference lists **9.4.1** as current.\[3\] | Highest API level supported: 37\[2\] |
| Gradle wrapper | **9.6.0** minimum and default for AGP 9.4\[2\] | Gradle 9.7.1 is the newest stable (19 Aug 2026), per secondary sources.\[4\] |
| JDK | 17, both minimum and default for AGP 9.4\[2\] | Use the JBR bundled with Studio |
| SDK Build Tools | 36.0.0 is AGP's default;\[2\] 37.0.0 is available |\[5\] |
| Kotlin | **2.4.20** (7 Sept 2026)\[6\] | AGP 9 compiles Kotlin itself, so you **don't** need to apply `org.jetbrains.kotlin.android`.\[7\] |
| compileSdk / targetSdk | **37** (Android 17, stable 16 June 2026) / 36 or 37\[8\] | core 1.19.x requires compileSdk ≥ 37. That comes from community build reports, so confirm it on your own build.\[9\] |
| minSdk | **26** | GLES 3.1 hardware and `PowerManager.getThermalHeadroom` (API 30) are gated at runtime |
| NDK (optional, Phase 2+) | 28.2.13676358 (r28c), AGP 9.x's default |\[2\]\[7\] |
| CMake (optional) | 3.22.1+ through the SDK Manager | Android docs give no official recommendation for AGP 9.x. 3.21+ is required for CMake's built-in NDK support.\[10\] |

### 3.2 Gradle coordinates (version catalog sketch)

```toml
[versions]
agp = "9.4.1"
kotlin = "2.4.20"
coreKtx = "1.19.1"
appcompat = "1.8.0"
activity = "1.13.0"
lifecycle = "2.11.0"
coroutines = "1.11.0"
media3 = "1.11.1"      # optional

[libraries]
androidx-core       = { module = "androidx.core:core", version.ref = "coreKtx" }          # core-ktx is now an empty shim
androidx-appcompat  = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }
androidx-activity   = { module = "androidx.activity:activity", version.ref = "activity" }  # registerForActivityResult
androidx-lifecycle-runtime = { module = "androidx.lifecycle:lifecycle-runtime", version.ref = "lifecycle" } # -ktx is empty since 2.8
androidx-lifecycle-viewmodel = { module = "androidx.lifecycle:lifecycle-viewmodel", version.ref = "lifecycle" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
# optional, comparison/reference only:
media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
media3-effect    = { module = "androidx.media3:media3-effect", version.ref = "media3" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
```

**Note on KTX:** Google is winding down the separate `-ktx` artifacts. Their extensions have been merged into the main libraries: `core-ktx` is an empty shim from 1.19.0 and `lifecycle-runtime-ktx` from 2.8.0.\[11\] Depend on `androidx.core:core` and `androidx.lifecycle:lifecycle-runtime` directly. The old `-ktx` coordinates still resolve if a template adds them.

### 3.3 Media3/ExoPlayer: is it useful here?

Media3 1.11.1 (Sept 2026)\[12\] supports `ExoPlayer.setVideoEffects(List<Effect>)`.\[13\] Custom GPU work goes in through `GlEffect`, which acts as a factory for a `GlShaderProgram`, often a subclass of `BaseGlShaderProgram`.\[14\]\[15\]\[16\] Those effects run on the GPU inside `DefaultVideoFrameProcessor`.\[17\]

**Recommendation: use raw MediaCodec for Phase 1.** Media3 stays a dev-only reference. Reasoning (this is my engineering assessment):
- Interpolation has to *create* frames with new timestamps and control exactly which vsync each one lands on. ExoPlayer's video renderer and frame processor own both the release timing and the frame count. The standard effects map one frame in to one frame out, and the community issue tracker shows instability with timestamp-altering wrappers.\[14\]\[18\]
- Holding two frames, caching motion vectors across output frames and using compute shaders all fit awkwardly in a `GlShaderProgram` chain.
- You also need direct access to `eglPresentationTimeANDROID`, GPU timer queries and the release cadence for the Go/No-Go measurements.
- **Where Media3 does help:** as a baseline player for A/B comparisons ("plain 30 fps" against yours), for its extractor and seeking code as reading material, and possibly later as a container for simple post-filters.

### 3.4 Platform APIs used (no extra dependencies)

| API | Purpose | Min API |
|---|---|---|
| `MediaExtractor`, `MediaFormat` | Demux, PTS, `KEY_FRAME_RATE`, rotation | 16 |
| `MediaCodec` (async), `MediaCodecList`, `MediaCodecInfo.VideoCapabilities` | HW decode and capability probe (`isHardwareAccelerated()` from API 29) | 21 |
| `SurfaceTexture`, `Surface` | Decoder output → `GL_TEXTURE_EXTERNAL_OES` | 11/14 |
| `EGL14`, `EGLExt.eglPresentationTimeANDROID` | Context, window surface, presentation time | 17/18 |
| `GLES31`, `GLES31Ext` | Compute shaders, image load/store, SSBOs | 21 |
| `Choreographer` (`postFrameCallback`; `postVsyncCallback`/`FrameData` on 33+) | Vsync-driven loop | 16/33 |
| `Surface.setFrameRate(fps, compat, strategy)` | Ask for a 60 Hz panel mode | 30/31\[19\] |
| `Display.getSupportedModes()`, `WindowManager.LayoutParams.preferredDisplayModeId` | List refresh rates (fallback below API 30) | 23\[20\] |
| `AudioTrack` (+ `getTimestamp`) | Audio output, master clock | 19 |
| `PowerManager.getThermalHeadroom(forecastSec)`, `addThermalStatusListener` | Thermal monitoring | 30 / 29\[21\]\[22\] |
| `BatteryManager` (`BATTERY_PROPERTY_CURRENT_NOW`, `CHARGE_COUNTER`) | Battery drain logging | 21 |
| Storage Access Framework (`ACTION_OPEN_DOCUMENT`) / Photo Picker (`PickVisualMedia.VideoOnly`) | Choosing files without storage permissions | 19 / backported |

### 3.5 Optional native and neural stack (Phase 3+, not needed now)

- **NDK + CMake:** only if you move hot code into C++, use `ASurfaceTransaction` (API 29) for finer presentation control, or adopt ncnn.
- **ncnn** (Tencent): BSD-3-Clause, Vulkan-backed.
- **rife-ncnn-vulkan**: MIT. **RIFE** (hzwer/ECCV2022-RIFE): MIT.\[23\]\[24\] Check the license and provenance of any model weights separately.

### 3.6 Licenses

| Component | License |
|---|---|
| AndroidX (core, appcompat, activity, lifecycle, Media3) | Apache 2.0\[25\] |
| Kotlin, kotlinx.coroutines | Apache 2.0\[26\] |
| Android platform APIs / SDK | Android SDK License (free) |
| Grafika (reference code) | Apache 2.0 (not an official Google product, and unmaintained)\[27\] |
| RIFE / rife-ncnn-vulkan | MIT\[23\]\[24\] |
| ncnn | BSD-3-Clause |
| FFmpeg (desktop tool for test clips, **not shipped**) | LGPL 2.1+/GPL depending on build |
| RenderDoc | MIT |
| Perfetto, Android GPU Inspector, scrcpy | Apache 2.0 |

### 3.7 Developer tools

| Tool | Use |
|---|---|
| **adb** (platform-tools 37.x)\[28\] | Install, logcat, `dumpsys SurfaceFlinger --latency <layer>` for actual present times,\[29\] `dumpsys gfxinfo`, `dumpsys thermalservice`, `dumpsys batterystats` |
| **Perfetto** (ui.perfetto.dev) | System traces of your own `Trace.beginSection` markers, frame timeline, CPU frequency, thermal. The main pacing debugger. |
| **Android GPU Inspector (AGI)** | GPU counters and frame profiling on supported Adreno/Mali devices. Check its device list before you rely on it. |
| **RenderDoc** | Capture a GLES frame and inspect textures and shaders, e.g. to check that the warped output is correct |
| **Arm Performance Studio / Snapdragon Profiler** | Vendor GPU counters (Mali / Adreno). Free. |
| **scrcpy** | Mirror the device screen for demos. Never use it to judge smoothness, because mirroring adds its own pacing. |
| **FFmpeg** | Make and convert test clips (§6) |

### 3.8 Manifest

```xml
<manifest ...>
    <!-- Require OpenGL ES 3.1 (Play filters devices without it) -->
    <uses-feature android:glEsVersion="0x00030001" android:required="true" />
    <!-- No READ_MEDIA_VIDEO / READ_EXTERNAL_STORAGE: use the system picker (SAF / Photo Picker) -->
    <application android:hardwareAccelerated="true" ...>
        <activity android:name=".PlayerActivity"
                  android:screenOrientation="sensorLandscape"
                  android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"
                  android:exported="true" />
    </application>
</manifest>
```

Use `ActivityResultContracts.OpenDocument(arrayOf("video/*"))` or `PickVisualMedia(VideoOnly)`. Call `takePersistableUriPermission` if you want a "recent files" list. Open the file with `contentResolver.openFileDescriptor(uri, "r")` and pass it to `MediaExtractor.setDataSource(fd)`.

---

## 4. Phase 1 Plan, Step by Step

Effort assumes one developer who knows Kotlin but is new to GL and codecs. "d" means focused working days.

### Step 1: Project setup, Hello GL on device, capability checks (3–4 d)
**Key focus:** a working EGL/GLES 3.1 context on a dedicated thread, plus a capability report you can trust.
- Create the project (Empty Views Activity), add the version catalog from §3.2, and enable USB debugging on both devices.
- Write `EglCore` (Grafika's `gles/EglCore.java` is the model): `eglGetDisplay`, `eglChooseConfig` with `EGL_OPENGL_ES3_BIT_KHR` and RGBA8888, a context with client version 3, and a window surface from the `SurfaceView` holder.
- Hello GL: clear to a colour that changes every vsync, driven by Choreographer.
- Capability probe, logged and shown on screen:
  - `GL_VERSION` and `GL_SHADING_LANGUAGE_VERSION`. Need 3.1 or higher.
  - Extensions: `GL_OES_EGL_image_external_essl3` (required), `GL_EXT_disjoint_timer_query`, `GL_EXT_color_buffer_half_float`, `GL_EXT_shader_framebuffer_fetch`.
  - Compute limits: `GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS` (spec minimum 128), `GL_MAX_COMPUTE_WORK_GROUP_SIZE`, `GL_MAX_COMPUTE_SHARED_MEMORY_SIZE` (minimum 16 KB).
  - `Display.getSupportedModes()`: list refresh rates, current mode.
  - `MediaCodecList(REGULAR_CODECS)`: AVC/HEVC decoders, `isHardwareAccelerated`, `VideoCapabilities.areSizeAndRateSupported(1920,1080,60)`.
  - Thermal: does `getThermalHeadroom(0)` return NaN? NaN means unsupported.\[21\]\[30\]

**Deliverables:** an app that shows the capability report, with an "export JSON" button.
**Acceptance:** runs on both devices. Context version reports ≥ 3.1. The external-essl3 extension is present, or the device goes on the "unsupported" list.

### Step 2: Basic video player (8–10 d)
**Key focus:** correct decode → SurfaceTexture → GL → screen, with A/V sync and seeking.
- Picker → `MediaExtractor.setDataSource(fd)` → select the video and audio tracks.
- Video `MediaCodec.createDecoderByType(mime)`, configured with `Surface(SurfaceTexture(oesTexId))`. Use asynchronous callback mode on a decoder HandlerThread.
- On the GL thread: `onFrameAvailable` → `updateTexImage()` → `getTransformMatrix(m)` → draw a full-screen quad with the OES shader (§5.1). Letterbox to preserve the aspect ratio and apply the `KEY_ROTATION` metadata.
- Audio: decode to PCM, then `AudioTrack` (`ENCODING_PCM_16BIT`, `MODE_STREAM`, buffer = 2–4× `getMinBufferSize`).
- Simple sync to start with: show each video frame when `ptsVideo ≤ audioClock()`. If a frame is more than 40 ms late, drop it. If it is early, wait.
- Play/pause: `AudioTrack.pause()`, freeze the clock, stop releasing output buffers.
- Seek: `extractor.seekTo(target, SEEK_TO_PREVIOUS_SYNC)` → `codec.flush()` → decode forward and **discard** frames (`releaseOutputBuffer(idx, false)`) until `pts ≥ target` → flush or reset the AudioTrack and re-anchor the clock.

**Deliverables:** a 30 fps player with a seek bar.
**Acceptance:** 10-minute H.264 1080p30 playback with lip-sync error under ±40 ms (clap test clip). A seek lands within 1 frame of the target. No leaked codecs after 50 open/close cycles.

### Step 3: Debug overlay and timing instrumentation (3–4 d)
**Key focus:** you can't optimize what you can't see. Build this before any interpolation.
- GPU timers: `GL_EXT_disjoint_timer_query`, with `glBeginQueryEXT(GL_TIME_ELAPSED_EXT)` / `glEndQueryEXT` around each stage (copy, pyramid, ME, filter, warp, present). Keep a ring of 3–4 query objects per stage and read results 2–3 frames later via `GL_QUERY_RESULT_AVAILABLE`. Throw the sample away if `GL_GPU_DISJOINT_EXT` is true. Results are in nanoseconds.\[31\]\[32\]
- CPU markers: `android.os.Trace.beginSection("interp")` for Perfetto.
- Overlay, drawn in GL or as a View on top: display fps, content fps, interpolated/duplicated/dropped counters, per-stage ms (p50/p95), A/V drift in ms, thermal headroom, current refresh rate.
- CSV logger: one row per presented frame (vsync time, intended present time, chosen A/B, t, path taken, stage ms).

**Acceptance:** the overlay costs under 0.5 ms GPU. The CSV opens in a spreadsheet or Python. Perfetto shows your sections.

### Step 4: 60 Hz presentation loop with frame duplication and correct pacing (5–6 d)
**Key focus:** perfect cadence *before* interpolation. Bad pacing is the most common reason "60 fps" still looks juddery.
- Switch to the display-driven pacer (§2.5). The decoder now only fills the ring buffer and the pacer decides what to show.
- Call `surface.setFrameRate(60f, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)`. On a 120 Hz panel you may get 120 Hz. Handle that by presenting each output on every second vsync, or by allowing 120 output frames per second later. Below API 30, use `preferredDisplayModeId`.
- Set `eglPresentationTimeANDROID` for each swap, targeting the vsync you computed.
- Duplication mode: t is forced to 0 (show A), so you should see an exact 2:2 cadence at 30→60.
- Verify with `adb shell dumpsys SurfaceFlinger --latency <layer>`\[29\] and the Perfetto FrameTimeline: every content frame should stay on screen for exactly 2 refreshes.

**Acceptance:** over 5 minutes, at least 99.5% of content frames are shown for exactly 2 vsyncs, with zero vsync misses on the flagship and at most 0.5% on mid-range. A/V drift stays within ±20 ms.

### Step 5: Two frames resident plus simple blend interpolation (4–5 d)
**Key focus:** the OES → RGBA copy, the ring buffer, and the first output that genuinely depends on t.
- Copy pass: OES → `GL_RGBA8` slot (applying the transform matrix), plus a luma pass at 1/4 resolution (`dot(rgb, vec3(0.299,0.587,0.114))`, box-downsampled) into `GL_R16F` or `R8`. Build a 1/8 or 1/16 level as well for hierarchical ME.
- Blend shader: `out = mix(A, B, t)`.
- Flip between modes at runtime: duplicate / blend.

**Acceptance:** blend runs under 1.5 ms at 1080p on mid-range. Shown side by side, blending looks smoother but ghosts on motion. That ghosting is why motion compensation (MC) is needed.

### Step 6: Motion vector generation by block matching (12–15 d)
**Key focus:** a smooth "true-motion" vector field, which matters far more than the lowest SAD (sum of absolute differences) per block. Decide early whether to run ME once per pair of source frames, at 30 Hz, and reuse its vectors for every t. Recommended: yes, which halves the ME cost at 30→60.

**Algorithm v1 (full search, coarse-to-fine):**
1. **Input:** luma of A and B at 1/4 resolution (480×270 for 1080p).
2. **Block size:** 8×8 at 1/4 resolution, which is 32×32 at full resolution. That gives a 60×34 vector grid for 1080p. 8×8 is the block size de Haan's 3DRS true-motion estimator uses for frame rate conversion.\[33\] At 1/4 resolution it balances detail against noise.
3. **Search range:** ±8 px at 1/4 resolution (±32 px full-res, about 1 px per ms of motion at 30 fps) for the first version. Add a 1/16-resolution level searching ±4 to reach about ±64–128 px full-res for fast pans, then refine ±2 at each finer level.
4. **Cost:** SAD over the block, plus a small smoothness penalty λ·|v − v_pred|, where v_pred is the median of neighbouring vectors from the coarser level or the previous field. The penalty is what pushes results towards "true motion" instead of noise.
5. **Symmetric (bilateral) option, Step 7b:** for an interpolated frame at t, search vectors centred on the intermediate frame, comparing A(p − t·v) against B(p + (1−t)·v). This avoids holes and overlaps when projecting forward.\[34\]\[35\] Start with forward ME (A→B) in v1 for simplicity.
6. **Sub-pixel:** fit a parabola to the SAD at ±1 around the minimum, giving about 0.25-px precision at 1/4 resolution.
7. **Post-filter:** a 3×3 vector median filter, then an optional bilateral filter guided by luma edges. Write the final field to a `GL_RG16F` texture, one texel per block.
8. **Confidence per block:** the minimum SAD normalized by block variance, plus forward-backward consistency once you also run B→A: |v_AB + v_BA| below a threshold.

**Algorithm v2 (if v1 is too slow): 3DRS-style candidate search.** Evaluate only about 8 candidates per block: spatial neighbours from the current field, temporal neighbours from the previous field, the zero vector, and a few random updates. This follows de Haan et al.'s 1993 IEEE TCSVT paper, which presented "a new recursive block-matching motion estimation algorithm with only eight candidate vectors per block". Semantic Scholar's summary of the paper says it "is shown to have a superior performance over alternative algorithms, while its complexity is significantly less", and US patent 6782054 notes that 3DRS was "used in the Philips Natural Motion TV sets." Its sequential dependencies don't fit a GPU naturally, so the standard fix is a checkerboard or wavefront order, or two or three Jacobi-style iterations.\[34\]

**GPU implementation (GLES 3.1 compute):**
- One work group per block row segment, e.g. `local_size_x = 8, local_size_y = 8` (64 invocations, safely under the 128 minimum), so that one invocation handles one candidate offset or one block.
- Load the reference tile (block plus search margin) into `shared` memory once. For an 8×8 block with ±8 search that is 24×24 texels. Then evaluate candidates from shared memory and reduce to the minimum SAD with `barrier()` and `memoryBarrierShared()`.
- Use `imageStore` into the `RG16F` vector texture and call `glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)` before the warp pass samples it.
- Prefer `mediump`/fp16 for SAD accumulation only if it doesn't overflow. Integer `uint` accumulation is safe.
- Fragment-shader fallback: render a 60×34 target and loop the search inside the fragment shader. It's slower but works on drivers with weak compute support.

**MV visualization debug mode:** draw arrows per block, or an HSV colour wheel (hue = direction, value = magnitude), over the video, plus a heatmap of confidence and SAD.

**Acceptance:** on a synthetic pan clip (§6) the median vector error is under 0.5 px at 1/4 resolution. The field looks coherent on real pans. ME takes ≤ 3 ms on the flagship and ≤ 5 ms on mid-range at 1080p input, run once per source pair.

### Step 7: Frame generation — bidirectional warp, blending, occlusion and confidence, scene cuts, fallbacks (10–12 d)
**Key focus:** artifact control matters more than raw sharpness. A fallback that degrades gracefully beats a clever warp that sometimes tears.
- **Upsample the vector field** from block resolution to pixel resolution by bilinear sampling of the `RG16F` texture. Optionally use edge-aware joint-bilateral upsampling against full-res luma to reduce the "block halo" at object edges.
- **Bidirectional warp at t** (fragment shader, full resolution). With flow F from A to B at the output pixel p:
  - `cA = texture(A, p − t·F)`, `cB = texture(B, p + (1−t)·F)`
  - Base weights: `wA = (1−t)`, `wB = t`
  - Occlusion and consistency: compute `|cA − cB|` (photometric disagreement). Where it is large, the pixel is probably occluded or uncovered in one frame. Favour the side with lower local SAD, or switch to the non-MC fallback for that pixel.
  - `out = (wA·cA·kA + wB·cB·kB) / (wA·kA + wB·kB)`, where k comes from confidence.
- **Per-pixel fallback blend:** `final = mix(blend_or_nearest(A,B,t), mc_out, confidence)`. With low confidence, either average A and B (when t is near 0.5) or repeat the nearest frame.
- **Scene-cut detection:** compare luma histograms or mean SAD between A and B at 1/16 resolution. If the difference crosses a threshold (calibrate on the scene-cut clips), skip interpolation for that pair and show A, then B. Also skip after a seek and on the first frame after a flush.
- **Global fallback triggers:** too many low-confidence blocks (over ~40%) → blend. Thermal headroom ≥ 0.9 or GPU budget overrun → duplicate. Fall back with hysteresis so the mode doesn't flicker.
- **Edges:** clamp sample coordinates. Use `CLAMP_TO_EDGE` on all RGBA textures.

**Acceptance:** the full pipeline (copy + pyramid + ME amortized + warp) stays ≤ 10 ms per presented frame at 1080p on the flagship and at 720p on mid-range. On pan and sports clips, blind A/B viewing rates it smoother than plain 30 fps, with no frequent tearing or block artifacts. Scene cuts show no cross-fade ghost.

### Step 8: Benchmarking, thermal and battery runs, Go/No-Go (5–7 d)
**Key focus:** sustained real-world numbers, not best-case numbers.
- Run the clip set (§6) on both devices in each mode: 30 fps baseline, duplicate, blend, MC.
- Sustained runs: 10 minutes for performance, then 30–60 minutes for thermal and battery, at 50% brightness, airplane mode, starting from 100% charge and room temperature.
- Log stage ms p50/p95/p99, vsync misses, mode fallbacks, thermal headroom over time, battery % per hour, and skin temperature if you have an IR thermometer.
- Blind subjective test: 3–5 viewers compare the baseline against MC on pans, sports and scrolling text.
- **Go/No-Go gates:**
  1. Interpolation under 10 ms per frame at 1080p (720p on mid-range), sustained 10+ minutes.
  2. Visibly smooth pacing: at least 99% correct cadence, no periodic stutter.
  3. Better than plain 30 fps on pan and sports clips (most viewers prefer it).
  4. Acceptable heat and battery over 30–60 minutes. A practical target is ≤ 1.5× the battery drain of baseline playback and no severe thermal status.
- **Pivots if a gate fails:** flagship only, a 720p processing cap (ME at 1/4 of 720p, warp at display resolution), or an offline "convert to 60 fps file" mode using MediaCodec encode.

---

## 5. Concrete Technical Details

### 5.1 Sampling the decoder texture (GL_TEXTURE_EXTERNAL_OES)

```glsl
// Vertex shader
#version 300 es
layout(location=0) in vec4 aPos;
layout(location=1) in vec4 aTex;
uniform mat4 uTexMatrix;           // from SurfaceTexture.getTransformMatrix()
out vec2 vUv;
void main() { gl_Position = aPos; vUv = (uTexMatrix * aTex).xy; }
```
```glsl
// Fragment shader: OES -> RGBA copy
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
in vec2 vUv;
out vec4 oColor;
void main() { oColor = texture(uTex, vUv); }
```
- For ESSL 3.x shaders (`#version 300 es` / `310 es`) the extension is **`GL_OES_EGL_image_external_essl3`**. The older `GL_OES_EGL_image_external` only defines ESSL 1.0 behaviour, and some drivers reject mixing them. Android's own platform code moved to the essl3 extension for this reason.\[36\]\[37\]\[38\]
- External textures only support `LINEAR`/`NEAREST` filtering and `CLAMP_TO_EDGE`, with no mipmaps.\[39\] That is another reason to copy into normal textures straight away.
- Bind with `glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)`.
- **Always** apply the transform matrix. It encodes the crop, the flip and sometimes the rotation, and differs between decoders.
- Don't sample the OES texture from compute shaders. Do the OES → RGBA copy in a fragment pass, because driver support for external samplers in compute is inconsistent.

### 5.2 Compute work groups on mobile
- Spec minimums for GLES 3.1: 128 invocations per group and 16 KB of shared memory. Treat 64 (8×8) as the safe default. Try 16×8 and 8×4 on each GPU family and keep what measures fastest.
- Mali (Valhall) and Adreno 6xx/7xx both run waves or warps of 16–64 lanes, so avoid tiny groups (≤ 16).
- Minimize `barrier()` calls, coalesce `imageLoad` reads, and prefer `texelFetch` on `sampler2D` for read-only inputs, which uses the texture cache.
- Check `glGetError` and the shader info logs at runtime. Compile every shader at startup and fall back to the fragment-shader path if compute linking fails.

### 5.3 Recommended block matching parameters (starting points)

| Parameter | Start | Range to explore | Why |
|---|---|---|---|
| ME resolution | 1/4 | 1/2 – 1/8 | 1/4 is about 16× fewer pixels |
| Block size | 8×8 @1/4 | 4×4 – 16×16 | 8×8 is the 3DRS FRC standard |
| Search range | ±8 @1/4 | ±4 – ±16 + pyramid | Covers ~32 px/frame full-res; the pyramid extends it |
| Pyramid levels | 2 (1/16, 1/4) | 1–3 | Large motion without a huge search |
| Cost | SAD + λ·smoothness | SAD, census, ZSAD | ZSAD (mean-removed) handles brightness changes |
| Vector filter | 3×3 median | 5×5, bilateral | Removes outliers and block noise |
| Sub-pixel | Parabolic | none / quarter-pel | Sharper warps |

These are engineering defaults drawn from the 3DRS and FRC literature. Expect to tune them per clip type.

### 5.4 Frame pacing APIs
- **Choreographer:** `postFrameCallback(cb)` on the GL Looper. Re-post the callback every frame. On API 33+, `postVsyncCallback` gives `FrameData` with several expected presentation timelines, so you can pick a deadline that fits your GPU budget.
- **`eglPresentationTimeANDROID`:** tells SurfaceFlinger the earliest time to show the buffer. Combined with the vsync-aligned `T_present`, it stops frames from bunching up.
- **`Surface.setFrameRate`:** for video, pass `FRAME_RATE_COMPATIBILITY_FIXED_SOURCE`. The platform may choose a multiple, e.g. 120 Hz for a 60 request, or ignore the request. Your app must still work correctly when the refresh rate doesn't change.\[40\] Here you are requesting the *output* rate (60), not the source rate (30). Call `setFrameRate(0, …)` to clear the request when playback stops but the surface stays visible.\[40\]
- **`ASurfaceTransaction`/`SurfaceControl`** (API 29+, NDK or Java API 29+): finer control over each buffer's present time. Keep it for Phase 2 if EGL pacing isn't good enough.

### 5.5 A/V sync approach
- Master clock = audio: `mediaTimeUs = anchorPts + (framePosition / sampleRate)·1e6 + (now − timestampNanoTime)/1000`, from `AudioTrack.getTimestamp()`. Poll every 0.5–1 s and interpolate with `System.nanoTime()` in between.
- Video follows audio. The pacer requests `c = mediaTimeAt(T_present)`. If the decoder can't keep up, drop source frames: skip them in the ring buffer, and if decode itself is the bottleneck, release them unrendered before the GPU copy.
- When there is no audio track, use the system monotonic clock anchored at play.

### 5.6 Reference code
- **Grafika** (Apache 2): read `MoviePlayer.java` (MediaCodec → Surface, with frame pacing via `SpeedControlCallback`), `gles/EglCore.java`, `gles/Texture2dProgram.java` (the OES shader and transform matrix), `PlayMovieSurfaceActivity`, and `ContinuousCaptureActivity`. Grafika targets API 18 era code, is "NOT AN OFFICIAL GOOGLE PRODUCT", and isn't maintained.\[27\] Port its ideas to Kotlin; don't copy it wholesale.
- **AOSP graphics architecture docs** (SurfaceTexture, TextureView, BufferQueue, SurfaceFlinger) explain why the one-buffer-at-a-time rule exists.\[41\]\[42\]
- **bigflake MediaCodec samples:** CTS-style decode-to-surface tests, by the same author as Grafika.\[43\]

---

## 6. Test Plan and Clip Set

### 6.1 Clips (generate or collect, keeping licenses clean)

| # | Content | Format | Purpose |
|---|---|---|---|
| C1 | Synthetic pan (known px/frame) | 1080p30 H.264 | Ground truth for ME accuracy |
| C2 | Slow horizontal camera pan, detailed scene | 1080p30 | Judder visibility |
| C3 | Fast pan / whip pan | 1080p30 | Search-range limit |
| C4 | Sports (football/basketball broadcast) | 1080p30, 720p30 | Many small moving objects plus a moving camera |
| C5 | Scrolling text / credits / ticker | 1080p30 | Regular patterns (aperture problem), shows artifacts clearly |
| C6 | Hard scene cuts every 2–5 s | 1080p30 | Scene-cut detector |
| C7 | Low light / noisy | 1080p30 | Noise robustness of SAD |
| C8 | Film 23.976/24 fps pan | 1080p24 | Arbitrary-t path (24→60) |
| C9 | Occlusion (person walking in front of a background) | 1080p30 | Occlusion handling |
| C10 | Static talking head | 1080p30 | Regression: interpolation must not *add* artifacts |
| C11 | Clap/beep sync clip | 1080p30 + AAC | A/V sync |
| C12 | Ground-truth 60 fps source, decimated to 30 | 1080p60 → 30 | PSNR/SSIM of interpolated frames against the dropped real frames |

**FFmpeg recipes:**
```bash
# Synthetic pan: 4 px/frame over a test pattern, 1080p30, 60 s
ffmpeg -f lavfi -i "testsrc2=size=3840x1080:rate=30" -vf "crop=1920:1080:'mod(n*4,1920)':0" \
  -t 60 -c:v libx264 -profile:v high -pix_fmt yuv420p -g 60 C1_pan4px.mp4
# Decimate 60 -> 30 for ground truth (keep even frames)
ffmpeg -i src60.mp4 -vf "select='not(mod(n,2))',setpts=N/30/TB" -r 30 -c:v libx264 -pix_fmt yuv420p C12_30.mp4
# Make a 720p version
ffmpeg -i in.mp4 -vf scale=-2:720 -c:v libx264 -crf 18 -c:a copy out_720p.mp4
# 24 fps
ffmpeg -i in.mp4 -vf fps=24000/1001 -c:v libx264 -crf 18 C8_24p.mp4
```
FFmpeg's own `minterpolate` filter (`-vf minterpolate=fps=60:mi_mode=mci:me=epzs`) makes a free offline quality reference to compare against.

### 6.2 Metrics to record
- **Performance:** per-stage GPU ms (copy, pyramid, ME, filter, warp, overlay), with p50/p95/p99. Total frame GPU ms. CPU ms on the GL thread.
- **Pacing:** % of content frames held for the correct number of vsyncs, vsync misses per minute, late-B events, dropped source frames, and present-time error (actual from SurfaceFlinger minus intended).
- **Sync:** A/V offset over time in ms.
- **Quality:** PSNR/SSIM on C12, ME end-point error on C1, % of low-confidence pixels, fallback-mode time share, and blind preference scores.
- **Thermal/battery:** thermal headroom and status over time, CPU/GPU frequency (Perfetto), battery %/h, `CURRENT_NOW` mA, and the time at which throttling or quality fallbacks begin.

---

## 7. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| **GPU driver fragmentation** (Mali vs Adreno vs Xclipse/PowerVR; compute bugs; missing essl3 external extension) | Crashes, wrong output\[36\]\[44\] | Capability probe and an allow-list. Keep a fragment-shader fallback for every compute pass. Validate shaders at startup. Test both vendors from Step 1. |
| **Thermal throttling** after 5–15 minutes | Missed 10 ms budget, stutter | Poll `getThermalHeadroom(10)` every few seconds.\[30\]\[45\] Adaptive quality ladder: MC 1080p → MC 720p processing → blend → duplicate. Run ME at 30 Hz only. |
| **Interpolation artifacts** (halos, tearing on text, occlusion smears) | Looks worse than 30 fps | Confidence-weighted fallback, median and bilateral vector filtering, scene-cut detection, conservative λ. Bias towards "repeat/blend when unsure". |
| **Pacing errors** (uneven 2:2, bunching, mis-predicted present times) | Smoothness gain lost | Build the display-driven pacer first (Step 4). Use `eglPresentationTimeANDROID`. Verify with SurfaceFlinger latency dumps and Perfetto. Never judge smoothness through scrcpy. |
| **Refresh-rate modes** (90/120/144 Hz panels, LTPO, OS ignoring `setFrameRate`) | Wrong cadence | Read the active refresh rate each frame from `Display.getRefreshRate()` / Choreographer intervals. The arbitrary-t design adapts automatically (e.g. at 120 Hz, t = 0, .25, .5, .75). Optionally cap output at 60 by presenting on alternate vsyncs. |
| **Decoder quirks** (stride/crop, rotation, B-frame PTS reordering, slow flush) | Distorted frames, bad seek | Always apply the SurfaceTexture matrix. Sort by PTS in the ring buffer. Seek to the previous sync frame and decode forward. |
| **HDR/10-bit** content | Wrong colours, unsupported EGL configs | Out of scope. Detect `KEY_COLOR_TRANSFER`/profile and either refuse or play without interpolation. Phase 3 can use RGBA1010102/FP16 and tone mapping. |
| **DRM content** | Secure surfaces can't be read by GL | Out of scope by design. Detect `MediaCrypto`/`KEY_IS_SECURE`/encrypted tracks and play through a normal path without interpolation. |
| **Battery drain** | Users reject it | Gate 4. Offer a "battery saver" mode (blend only). Stop the pipeline when paused. |
| **Developer learning curve with GL** | Schedule slip | Step-by-step gates, RenderDoc from Step 5 onwards, small shaders, reuse Grafika patterns. |

---

## 8. Timeline and Milestones

| Week | Milestone | Exit criteria |
|---|---|---|
| 1 | **M1: Capabilities and Hello GL** (Step 1) | Report on both devices |
| 2–3 | **M2: Player with A/V sync and seek** (Step 2) | 10-min lip-sync test passes |
| 4 | **M3: Instrumentation** (Step 3) | Overlay + CSV + Perfetto markers |
| 4–5 | **M4: 60 Hz pacing (duplicate)** (Step 4) | ≥ 99.5% correct cadence |
| 5–6 | **M5: Ring buffer and blend** (Step 5) | Blend under 1.5 ms |
| 6–9 | **M6: Motion vectors + visualization** (Step 6) | ME ≤ 3/5 ms, coherent fields |
| 9–11 | **M7: MC frame generation + fallbacks** (Step 7) | ≤ 10 ms total; better than 30 fps in A/B viewing |
| 11–13 | **M8: Benchmark and Go/No-Go** (Step 8) | Decision memo with data |

Total: **about 11–13 weeks** for one developer, including roughly 15% buffer for driver issues.

**Later phases (outline)**
- **Phase 2: quality and polish.** Dense optical flow on the GPU (DIS-style: inverse-search patch matching, multi-scale densification, optional variational refinement). The arXiv:1603.03590 abstract by Kroeger, Timofte, Dai and Van Gool says DIS "runs at 300Hz up to 600Hz on a single CPU core", "order(s) of magnitude faster than state-of-the-art methods in the same range of accuracy", so a GPU port is plausible. Also: forward-backward occlusion masks, edge-aware upsampling, 24→60 and 120 Hz support, HEVC, `ASurfaceTransaction` pacing, and a Jetpack Compose UI wrapped around the same engine (`AndroidView` hosting the SurfaceView).
- **Phase 3: neural path (flagship only).** RIFE through ncnn + Vulkan (rife-ncnn-vulkan, MIT).\[23\] Check model licenses, measure at 540p and upscale the flow, and use it as a "high-quality" tier. Also HDR/10-bit.
- **Phase 4: iOS.** AVAssetReader/AVPlayerItemVideoOutput → CVMetalTextureCache → Metal compute ports of the same shaders (block-matching ME, warp), with CADisplayLink pacing. Keep the interpolator's algorithm specification platform-neutral now so it can be ported.

---

## 9. Learning Resources and Reference Implementations

**Official Android**
- Media codec reference: developer.android.com/reference/android/media/MediaCodec
- SurfaceTexture architecture: source.android.com/docs/core/graphics/arch-st
- TextureView architecture (Grafika case studies): source.android.com/docs/core/graphics/arch-tv
- Frame rate guide (`Surface.setFrameRate`): developer.android.com/media/optimize/performance/frame-rate
- High refresh rate rendering (Android Developers Blog, 2020): android-developers.googleblog.com/2020/04/high-refresh-rate-rendering-on-android.html
- Thermal API (ADPF): developer.android.com/games/optimize/adpf/thermal
- PowerManager reference: developer.android.com/reference/android/os/PowerManager
- Media3 effects / Transformer custom effects: developer.android.com/media/media3/transformer/transformations
- AGP 9.4.0 release notes: developer.android.com/build/releases/agp-9-4-0-release-notes
- Media3 releases: developer.android.com/jetpack/androidx/releases/media3

**Reference code**
- Grafika: github.com/google/grafika
- bigflake MediaCodec samples: bigflake.com/mediacodec
- androidx/media (ExoPlayer, effect module source): github.com/androidx/media
- RIFE: github.com/hzwer/ECCV2022-RIFE (MIT). Practical-RIFE for newer models.\[24\]
- rife-ncnn-vulkan: github.com/nihui/rife-ncnn-vulkan (MIT)\[23\]
- ncnn: github.com/Tencent/ncnn

**Khronos specs**
- OES_EGL_image_external_essl3: registry.khronos.org/OpenGL/extensions/OES/OES_EGL_image_external_essl3.txt
- EXT_disjoint_timer_query: registry.khronos.org/OpenGL/extensions/EXT/EXT_disjoint_timer_query.txt
- OpenGL ES 3.1 spec and ESSL 3.10 spec (compute shaders, limits): registry.khronos.org/OpenGL/index_es.php

**Motion estimation and interpolation literature**
- G. de Haan, P. Biezen, H. Huijgen, O. Ojo, "True-motion estimation with 3-D recursive search block matching," IEEE TCSVT 3(5):368–379, 1993 (doi:10.1109/76.246088). It introduced 3DRS with only eight candidate vectors per block.\[34\]\[46\]
- G. de Haan, P. Biezen, "Sub-pixel motion estimation with 3-D recursive search block-matching," Signal Processing: Image Communication 6, 1994.\[47\]
- T. Kroeger, R. Timofte, D. Dai, L. Van Gool, "Fast Optical Flow Using Dense Inverse Search," ECCV 2016 (arXiv:1603.03590). The OpenCV `DISOpticalFlow` implementation is useful for desktop prototyping.\[48\]\[49\]
- Z. Huang et al., "Real-Time Intermediate Flow Estimation for Video Frame Interpolation" (RIFE), ECCV 2022. Its arXiv:2011.06294 abstract says RIFE "can support arbitrary-timestep frame interpolation with the temporal encoding input", which matches the t ∈ [0,1] interface used here, and reports that "compared with the popular SuperSlomo and DAIN methods, RIFE is 4--27 times faster and produces better results."
- Papers on bilateral ME and hierarchical 3DRS for MCFI (Springer/IEEE), for hole- and overlap-free interpolation.\[33\]\[35\]

**Learning GL as a newcomer:** learnopengl.com (desktop GL, but the concepts carry over), the Arm Mali GPU Best Practices guide, the Qualcomm Adreno OpenGL ES developer guide, and RenderDoc tutorials.

---

## Caveats
- **Version numbers** are taken from official release pages as of 3 Oct 2026 where possible: AGP 9.4.0/9.4.1, Studio Rabbit 1 2026.2.1, Kotlin 2.4.20, core 1.19.1, appcompat 1.8.0, activity 1.13.0, lifecycle 2.11.0, Media3 1.11.1. Others come from Maven listings or secondary sources: coroutines 1.11.0, Gradle 9.7.1 as the newest stable, and the claim that core 1.19 needs compileSdk 37. Re-check in Studio's dependency update hints before you start.
- **Effort estimates, block-matching parameters and the ms budgets for each stage** are engineering judgements, not measured results. Step 8 exists to replace them with data.
- **The Media3 assessment** reflects how its effects pipeline is designed and what public issue reports show. A custom `GlShaderProgram` that emits extra frames may be possible, but Phase 1 shouldn't depend on it.
- **Tool availability** (AGI device support, vendor profilers) changes over time. Confirm support for your exact devices.
- **RIFE/ncnn code is MIT/BSD**, but trained model weights and any datasets can carry other terms. Review them before you ship a neural tier.

## Sources

1. [Android Studio Release Updates: Android Studio Quail 4 Patch 1 now available](https://androidstudio.googleblog.com/2026/09/android-studio-quail-4-patch-1-now.html)
2. [Android Gradle plugin 9.4.0 (September 2026) | Android Studio | Android Developers](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
3. [Android Gradle plugin API reference](https://developer.android.com/reference/tools/gradle-api)
4. [Gradle](https://en.wikipedia.org/wiki/Gradle)
5. [Update Android target/compile SDK to API 37 (Android 17) by oers · Pull Request #93 · oers/reversatile](https://github.com/oers/reversatile/pull/93)
6. [What's new in Kotlin 2.4.20](https://kotlinlang.org/docs/whatsnew2420.html)
7. [Android Gradle plugin 9.0.1 (January 2026)](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
8. [Android 17](https://en.wikipedia.org/wiki/Android_17)
9. [chore: bump compileSdk to 37 (Android 17) by deekue · Pull Request #49 · deekue/linkrouter](https://github.com/deekue/linkrouter/pull/49)
10. [Configure CMake](https://developer.android.com/studio/projects/configure-cmake)
11. [An update on Android KTX](https://jakewharton.com/an-update-on-android-ktx/)
12. [Media3](https://developer.android.com/jetpack/androidx/releases/media3)
13. [Transformations](https://developer.android.com/media/media3/transformer/transformations)
14. [github.com](https://github.com/androidx/media/issues/1422)
15. [Trim, Transcode, Concatenate: Your Guide to Media3 Editing Libraries](https://medium.com/google-exoplayer/trim-transcode-concatenate-your-guide-to-media3-editing-libraries-668b4e4c2f97)
16. [media/libraries/effect/src/main/java/androidx/media3/effect/GlEffect.java at release · androidx/media](https://github.com/androidx/media/blob/release/libraries/effect/src/main/java/androidx/media3/effect/GlEffect.java)
17. [\[Feature Req\] Add Video Filters (Brightness, Contrast, Saturation) for Media3/Exoplayer Playback · Issue #1971 · anilbeesetti/nextplayer](https://github.com/anilbeesetti/nextplayer/issues/1971)
18. [TimestampWrapper Effect with exoPlayer.setVideoEffects causes crash · Issue #821 · androidx/media](https://github.com/androidx/media/issues/821)
19. [Surface.SetFrameRate Method (Android.Views)](https://learn.microsoft.com/en-us/dotnet/api/android.views.surface.setframerate?view=net-android-34.0)
20. [Android Developers Blog: High refresh rate rendering on Android](https://android-developers.googleblog.com/2020/04/high-refresh-rate-rendering-on-android.html)
21. [PowerManager.GetThermalHeadroom(Int32) Method (Android.OS)](https://learn.microsoft.com/en-us/dotnet/api/android.os.powermanager.getthermalheadroom?view=net-android-35.0)
22. [Integrating Adaptability features into your Native Game](https://developer.android.com/codelabs/adaptability-codelab)
23. [GitHub - nihui/rife-ncnn-vulkan: RIFE, Real-Time Intermediate Flow Estimation for Video Frame Interpolation implemented with ncnn library · GitHub](https://github.com/nihui/rife-ncnn-vulkan)
24. [GitHub - hzwer/ECCV2022-RIFE: ECCV2022 - Real-Time Intermediate Flow Estimation for Video Frame Interpolation · GitHub](https://github.com/hzwer/ECCV2022-RIFE)
25. [androidx.media3:media3-exoplayer 1.11.1 on Maven - Libraries.io - security & maintenance data for open source software](https://libraries.io/maven/androidx.media3:media3-exoplayer)
26. [org.jetbrains.kotlinx:kotlinx-coroutines-android - Maven Central](https://central.sonatype.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-android)
27. [github.com](https://github.com/pacino/grafika)
28. [Android Debug Bridge](https://en.wikipedia.org/wiki/Android_Debug_Bridge)
29. [The Android video view requests a wrong refresh rate and film video judders · Issue #137 · yuroyami/KitePlayer](https://github.com/yuroyami/KitePlayer/issues/137)
30. [Thermal API](https://developer.android.com/games/optimize/adpf/thermal)
31. [GL\_EXT\_disjoint\_timer\_query for performance - Mobile, Graphics, and Gaming forum - Forums - Arm Community](https://community.arm.com/forums/f/mobile-graphics-and-gaming-forum/6869/gl_ext_disjoint_timer_query-for-performance)
32. [EXT\_disjoint\_timer\_query: getQueryObjectEXT() method](https://developer.mozilla.org/en-US/docs/Web/API/EXT_disjoint_timer_query/getQueryObjectEXT)
33. [Optimization of Hierarchical 3DRS Motion Estimators for Picture Rate Conversion](https://www.researchgate.net/publication/220373155_Optimization_of_Hierarchical_3DRS_Motion_Estimators_for_Picture_Rate_Conversion)
34. [(PDF) Real-time 3DRS motion estimation for frame-rate conversion](https://www.researchgate.net/publication/325741084_Real-time_3DRS_motion_estimation_for_frame-rate_conversion)
35. [Frame Interpolation Algorithm Using Improved 3-D Recursive Search](https://link.springer.com/chapter/10.1007/978-3-030-03398-9_18)
36. [1507074 - Shader compilation failed when GL\_OES\_EGL\_image\_external\_essl3 is not supported on android device](https://bugzilla.mozilla.org/show_bug.cgi?id=1507074)
37. [GL\_OES\_EGL\_image\_external\_essl3](https://docs.imgtec.com/reference-manuals/open-gl-es-extensions/html/topics/GL_OES_EGL/image-external-essl3.html)
38. [Diff - c06aeb2ddb11c9adb7995b563222d804971c2860^! - platform/frameworks/av - Git at Google](https://android.googlesource.com/platform/frameworks/av/+/c06aeb2ddb11c9adb7995b563222d804971c2860%5E!/)
39. [extensions/OES/OES\_EGL\_image\_external\_essl3.txt - external/github.com/KhronosGroup/OpenGL-Registry - Git at Google](https://skia.googlesource.com/external/github.com/KhronosGroup/OpenGL-Registry/+/refs/heads/255-update/extensions/OES/OES_EGL_image_external_essl3.txt)
40. [Frame rate](https://developer.android.google.cn/guide/topics/media/frame-rate?hl=fa&skip_cache=true%22)
41. [SurfaceTexture](https://source.android.com/docs/core/graphics/arch-st)
42. [TextureView](https://source.android.com/docs/core/graphics/arch-tv)
43. [GitHub - google/grafika: Grafika test app · GitHub](https://github.com/google/grafika)
44. [GLSL compilation error (skia) · Issue #17102 · flutter/flutter](https://github.com/flutter/flutter/issues/17102)
45. [PowerManager](https://developer.android.com/reference/android/os/PowerManager)
46. [A fast quaternion wavelet-based motion compensated frame rate up-conversion with fuzzy smoothing: application to echocardiography temporal enhancement](https://link.springer.com/article/10.1007/s11042-020-09834-1)
47. [Motion estimation and motion-compensated interpolition](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/6385245)
48. [\[PDF\] Fast Optical Flow Using Dense Inverse Search](https://www.semanticscholar.org/paper/Fast-Optical-Flow-Using-Dense-Inverse-Search-Kroeger-Timofte/9ceb5b92cad5cb135aaab43d4f25a6e34afe6e9f)
49. [DISOpticalFlow Class](https://www.emgu.com/wiki/files/4.5.1/document/html/fcbf8910-b492-8b85-ac2e-0a40ae514be4.htm)
