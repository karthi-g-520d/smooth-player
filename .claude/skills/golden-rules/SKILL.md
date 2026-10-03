---
name: golden-rules
description: Kotlin/Android coding conventions for the SmoothPlayer frame-interpolation video player — module structure (engine api/impl split), MVI-style UI state, threading rules for codec/GL/audio, MediaCodec lifecycle, GLES/EGL rules, GLSL shader conventions, testing, and git/docs conventions. Load before writing any Kotlin, Gradle, or shader code in this repo.
---

# GOLDEN RULES — Android Frame-Interpolation Player (Kotlin · Views → Compose · MediaCodec · GLES 3.1 · GLSL)

**Bottom line:** Follow one rule above all: the UI owns no media or GL logic. Each screen is driven by one immutable `UiState` that a pure `reduce()` function produces (the Kotlin version of `useReducer`). The engine sits behind a small `:engine:api` contract and never imports UI code. Every hot path (codec callbacks, the GL loop, audio) runs on a dedicated, named `HandlerThread` that owns its resources and releases them explicitly. Everything else here is detail that serves that rule.

## TL;DR
- **Structure:** start with 4–5 Gradle modules, not 9: `:app`, `:feature:player`, `:engine:api` (pure Kotlin), `:engine:impl` (all Android media/GL code, split into packages), plus `build-logic`. This follows the Now in Android api/impl pattern and Google's modularization advice to "prefer `implementation` over `api`". Split `:engine:impl` into `:engine:decoder`, `:engine:gl` and so on only when a package outgrows it or when NDK code arrives.
- **State & code style:** use a *pragmatic MVI* screen. That means one `PlayerUiState` data class, a sealed `PlayerEvent`, a pure `reduce(state, event)`, and a single `uiState: StateFlow`. Google's recommendations call for exactly this ("expose UI state using a single property called uiState… make uiState a StateFlow"). Units get value classes (`Micros`, `Nanos`), constants go in `*Constants.kt` / `res/values`, and library modules use explicit API mode.
- **Hot paths:** coroutines handle orchestration, I/O and the UI. `HandlerThread`s plus `MediaCodec.setCallback(cb, handler)` handle codecs, and a single GL thread owns EGL, `SurfaceTexture` and Choreographer. Shaders use explicit `layout(binding/location)`, constants are injected from Kotlin as `#define`s so the two sides can never drift, the default precision is `mediump` and `highp` is used for coordinates and motion vectors.

---

## 0. Golden Rules Cheat Sheet (print this)

1. **One feature = one package folder** (`feature/player/`). Each folder holds `PlayerFragment.kt`, `PlayerViewModel.kt`, `PlayerContract.kt` (state, events, effects: your `types.ts`), `PlayerReducer.kt`, `PlayerConstants.kt` and `res/layout/fragment_player.xml`.
2. **UI is thin.** Fragments and Activities only bind views, collect `uiState`, render it and forward user actions as `PlayerEvent`s. They contain no `if` logic about media.
3. **One state object per screen.** `data class PlayerUiState(...)` is immutable and exposed as `StateFlow<PlayerUiState>`. Never expose `MutableStateFlow`.
4. **All state changes go through `reduce(state, event): state`**, a pure function with no Android imports, unit-tested in plain JVM.
5. **Engine never imports UI; UI never imports engine internals.** `:feature:player` depends only on `:engine:api`.
6. **Prefer `implementation` over `api`** in Gradle.\[1\] Use `internal` by default in Kotlin and turn on explicit API mode in library modules.
7. **No hardcoded versions.** Every dependency and plugin comes from `gradle/libs.versions.toml`, and shared build config lives in `build-logic` convention plugins.
8. **No raw `Long` for time.** Use `@JvmInline value class Micros`/`Nanos`; conversions live in one file (`Units.kt`).
9. **No magic numbers.** Use `private const val` at the top of the file or `*Constants.kt` per module. UI numbers, colors and strings go in `res/values/*.xml`.
10. **Sealed types for every state machine and result:** `DecoderState`, `PlayerEvent`, `EngineError`, `FrameDecision`.
11. **Every native resource is a class with `release()` (or `Closeable`), one owner and one owning thread.** Codecs, EGL contexts, textures, FBOs, programs and AudioTrack all follow this.
12. **GL calls only on the GL thread.** Annotate with `@GlThread` and check with `checkGlThread()` in debug builds.
13. **Codec callbacks go to a decoder `HandlerThread`**, never to the main looper.
14. **Inject dispatchers; never hardcode `Dispatchers.IO`/`Default`; never use `GlobalScope`.**\[2\]
15. **The ViewModel launches coroutines**; lower layers expose `suspend` functions and `Flow`.\[2\]\[3\]
16. **No allocation per frame in steady state** (Grafika's rule: "GC pauses cause jank").\[4\]
17. **Shaders:** `.vert/.frag/.comp`; `#version 310 es` on line 1; explicit `layout(location/binding)`; `u_`/`a_`/`v_`/`o_` prefixes; constants injected from Kotlin.
18. **`mediump` by default in fragment and compute shaders; `highp` for UVs, positions and motion vectors.**
19. **Debug builds:** GL error checks, a debug overlay, verbose logs and strict mode. **Release builds:** none of these.
20. **Trace every stage** with a fixed section name (`FI:decode`, `FI:me`, `FI:warp`), ≤127 code units, as a constant.\[5\]
21. **Pure logic gets JVM unit tests** (reducer, pacer math, scene-cut). **Codec and GL code gets instrumented tests.** **Shaders get golden-image tests.**
22. **Conventional Commits** with module scope: `feat(interp): add scene-cut detector`.
23. **Every module has a README; every irreversible decision gets an ADR.**

---

## 1. React TS → Android/Kotlin Mapping

| Your React TS rule | Android/Kotlin equivalent in this project |
|---|---|
| Component folder | Feature package `feature/player/` (and later a Gradle module `:feature:player`) |
| Component file header / body / footer | Fragment: **header** = binding + `viewModel` + collectors in `onViewCreated`; **body** = `render(state)`; **footer** = listeners → `onEvent()`, cleanup in `onDestroyView` |
| `useReducer(reducer, init)` | `PlayerViewModel` holding `MutableStateFlow(PlayerUiState())` + pure `PlayerReducer.reduce(state, event)` |
| `dispatch(action)` | `viewModel.onEvent(PlayerEvent.PlayPauseClicked)` |
| Action union type | `sealed interface PlayerEvent` |
| Custom hook `useQuery` | Repository/engine API exposing `Flow<T>` / `suspend fun`; ViewModel collects it in `viewModelScope` |
| `types.ts` | `PlayerContract.kt` (UI types) and `:engine:api` `model/*.kt` (domain types, value classes) |
| `constants.ts` | `PlayerConstants.kt`, `private const val` at file top, companion `const val`, `res/values/integers.xml` |
| `.scss` | `res/values/themes.xml`, `styles.xml`, `colors.xml`, `dimens.xml`; layouts in `res/layout/*.xml` |
| Props | Constructor parameters / `data class` config objects (`EngineConfig`) |
| `useEffect` cleanup | `onDestroyView` (null the binding), `repeatOnLifecycle`, `DefaultLifecycleObserver`, engine `release()` |
| Context/Provider | Manual DI container (`AppContainer`) in `:app`; Hilt optional later |
| Strict TS types | Kotlin null-safety, sealed types, value classes, `explicitApi()` |
| ESLint/Prettier | ktlint + detekt + Android Lint |

---

## 2. Recommended Folder Tree

```
FrameInterp/
├─ build-logic/convention/src/main/kotlin/
│   ├─ AndroidApplicationConventionPlugin.kt
│   ├─ AndroidLibraryConventionPlugin.kt     # compileSdk/minSdk/JVM target/lint
│   └─ KotlinJvmLibraryConventionPlugin.kt   # for :engine:api
├─ gradle/libs.versions.toml
├─ settings.gradle.kts                        # includeBuild("build-logic")
├─ app/                                       # wiring only: Application, AppContainer, MainActivity
├─ feature/player/
│   └─ src/main/kotlin/com/acme/fi/feature/player/
│       ├─ PlayerFragment.kt
│       ├─ PlayerViewModel.kt
│       ├─ PlayerContract.kt                  # UiState, Event, Effect
│       ├─ PlayerReducer.kt
│       └─ PlayerConstants.kt
│     src/main/res/{layout,values}/
├─ engine/api/            # pure Kotlin (kotlin("jvm")): NO android.* imports
│   └─ com/acme/fi/engine/api/
│       ├─ PlaybackEngine.kt                  # interface + EngineState Flow
│       ├─ model/Units.kt                     # Micros, Nanos, FrameIndex
│       ├─ model/EngineModels.kt              # VideoInfo, EngineConfig, InterpMode
│       └─ model/EngineError.kt               # sealed errors
├─ engine/impl/           # Android library: all platform code, packages = future modules
│   ├─ src/main/kotlin/com/acme/fi/engine/
│   │   ├─ capability/  demux/  decoder/  gl/  interp/  pacer/  audio/  telemetry/
│   │   ├─ threading/   (GlThread annotation, EngineThreads, checkThread)
│   │   └─ DefaultPlaybackEngine.kt
│   └─ src/main/assets/shaders/
│       ├─ common/{header.glsl,bindings.glsl,color.glsl}
│       ├─ oes_to_luma.frag  fullscreen.vert
│       ├─ me_block_match.comp  mv_filter.comp
│       ├─ warp.frag  blend.frag  debug_view.frag
├─ docs/adr/0001-single-gl-thread.md
└─ .github/workflows/ci.yml
```

Later phases add `engine/native/` (CMake + JNI), `engine/ncnn/` and a sibling `ios/` repo or folder.

---

## 3. Section-by-Section Golden Rules

### 3.1 Project & Module Structure (Gradle multi-module)

**Purpose:** let the compiler enforce "UI ↔ engine independence" so it doesn't rest on discipline alone.

**Recommendation for a solo spike:** don't create 9 engine modules on day one. Now in Android's own docs say its aim was "to find the right balance between overmodularizing a relatively small app" and showcasing patterns, and that "there isn't one right answer".\[6\] A dev.to post by Lucy, "The Future of Android App Architecture with Modularization and Multi Module Projects", states that "over-modularization is the most typical trap"; one dev.to author argues premature modularization "not only drains your resources but can also hinder the long-term success of your project" because early module boundaries go stale. Your project is the exception that justifies *some* modules. You need the engine to be UI-free so it can serve Compose and iOS later, and a module boundary is the only thing that enforces that. So use **api/impl with packages inside impl**:

| Module | Type | May depend on | Must NOT depend on |
|---|---|---|---|
| `:engine:api` | Kotlin/JVM | kotlinx-coroutines-core | anything `android.*`, any other module |
| `:engine:impl` | Android library | `:engine:api` (`api`), androidx.core, androidx.tracing | `:feature:*`, `:app` |
| `:feature:player` | Android library | `:engine:api`, lifecycle, appcompat | `:engine:impl` |
| `:app` | Application | everything (wiring only) | — |

**Rules**
- Mirror NiA's feature rule: "A feature's api module should not depend on another feature's api or impl module. A feature's impl should only depend on another feature's api module."\[7\]
- Use `implementation(...)` by default. Use `api(...)` only when a type from the dependency appears in your public signatures (e.g. `:engine:impl` exposes `:engine:api` types). Google's modularization patterns page puts it plainly: "When declaring dependencies in your module, prefer implementation over api."\[1\]
- Package naming: `com.acme.fi.<layer>.<module>.<subpackage>`, all lowercase, no underscores.
- Inside `:engine:impl`, packages talk to each other only through `internal` interfaces. Write them as if they were already modules. **Split trigger:** a package passes ~25 files, needs its own dependencies (NDK, ncnn), or its build times hurt.
- Convention plugins live in `build-logic` (an included build, not `buildSrc`). NiA's build-logic README describes them as "additive and composable" and says one-off logic belongs "directly in the module's build.gradle".\[8\]

**Example (illustrative):**
```kotlin
// settings.gradle.kts
pluginManagement { includeBuild("build-logic"); repositories { google(); mavenCentral(); gradlePluginPortal() } }
include(":app", ":feature:player", ":engine:api", ":engine:impl")

// feature/player/build.gradle.kts
plugins { alias(libs.plugins.fi.android.library) }
android { namespace = "com.acme.fi.feature.player" }
dependencies {
    implementation(projects.engine.api)          // type-safe project accessors
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.fragment.ktx)
}
```

### 3.2 Kotlin Language Rules

**File layout (the "header/body/footer" of a Kotlin class).** The official Kotlin conventions order is: (1) property declarations and initializer blocks, (2) secondary constructors, (3) method declarations, (4) companion object.\[9\] They also say "Do not sort the method declarations alphabetically or by visibility… put related stuff together".\[10\]\[11\] Our mandatory order:

```
// HEADER
package …
import …                          // no wildcard imports
private const val TAG = "FI/Decoder"   // file-private constants first

/** KDoc: what it owns, which thread it runs on. */
@GlThread internal class Foo(      // constructor params = "props"
    private val config: FooConfig,
) {
    // BODY
    // 1. state/properties (private first, then read-only public)
    // 2. init {}
    // 3. public API (in call order: configure → start → … → release)
    // 4. private helpers placed right after the public function that uses them
    // FOOTER
    // 5. companion object (factory fns / public const val only)
}
// 6. extension functions relevant to all clients of this class (same file)
```

**Naming:** `PascalCase` types; `camelCase` functions and properties; `SCREAMING_SNAKE` for `const val`; Kotlin files are named after their main type. Use suffixes consistently: `*UiState`, `*Event`, `*Effect`, `*Reducer`, `*Constants`, `*Config`, `*Error`, `*Impl` (only when an interface exists).

**Typing rules**
- Value classes for units. They prevent `pts / 1000` bugs at zero runtime cost in most positions. They get boxed when nullable or used as generics, so don't put them in `List<Micros?>` on hot paths.
- `sealed interface` for state, events and errors, so `when` is exhaustive (no `else` branch).
- Nullability: `!!` is banned outside tests. `lateinit` is allowed only for Android-injected views and bindings. Model "not ready yet" as a sealed state, not as `null`.
- Return `Result`-like sealed types (`EngineResult<T>`) across module boundaries. Throw only for programmer errors (`check`, `require`).
- Immutability: `val` + `data class` + `List` (not `MutableList`) in public signatures; use `copy()` to change state.
- Visibility: `internal` by default in `:engine:impl`. Turn on **explicit API mode** (`kotlin { explicitApi() }`) in `:engine:api` and `:engine:impl`. Kotlin's library guidelines say it "forces you to explicitly state your intentions when you're designing the API", and Jetpack libraries themselves follow it.\[12\]\[13\] Known friction: detekt's `RedundantVisibilityModifierRule` conflicts with explicit API mode, so disable that rule.\[14\]

**Example (illustrative) — `engine/api/model/Units.kt`:**
```kotlin
public const val MICROS_PER_SECOND: Long = 1_000_000L
public const val NANOS_PER_MICRO: Long = 1_000L

@JvmInline public value class Micros(public val value: Long) : Comparable<Micros> {
    public operator fun plus(o: Micros): Micros = Micros(value + o.value)
    public operator fun minus(o: Micros): Micros = Micros(value - o.value)
    override fun compareTo(other: Micros): Int = value.compareTo(other.value)
    public fun toNanos(): Nanos = Nanos(value * NANOS_PER_MICRO)
}
@JvmInline public value class Nanos(public val value: Long) {
    public fun toMicros(): Micros = Micros(value / NANOS_PER_MICRO)
}
@JvmInline public value class FrameIndex(public val value: Int)

public sealed interface EngineError {
    public data class CodecFailed(val diagnostic: String, val recoverable: Boolean) : EngineError
    public data class UnsupportedFormat(val mime: String) : EngineError
    public data class GlFailed(val glError: Int, val where: String) : EngineError
    public data object ThermalThrottled : EngineError
}
```

**Tooling:** ktlint (formatting, official Kotlin style) + detekt (complexity, magic numbers via `MagicNumber`, `ForbiddenComment`) + Android Lint with `warningsAsErrors = true` in CI.

### 3.3 Android UI Layer (Views now, Compose later)

**Pattern decision: MVVM vs MVI.** Google's architecture guide doesn't use the word "MVI". It does mark UDF "Strongly recommended" and recommends a single `uiState` `StateFlow`, which is MVI-shaped MVVM.\[15\] Community articles split predictably. A DZone article, "Using Jetpack Compose With MVI Architecture", argues that "MVI represents the entire UI state in one immutable data structure, preventing inconsistent or fragmented states", while others say "smaller apps can still thrive on MVVM's simplicity", and a CodeElevation Medium post lists MVI as "Overkill for Simple UIs — For basic screens or small apps, MVI's structure may be more than you need." **Recommendation:** go *pragmatic MVI*: one state, sealed events, pure reducer. Skip MVI frameworks (Orbit, MVIKotlin). This is the closest match to your `useReducer` habit, and the player screen really is a state machine (Idle → Loading → Playing/Paused → Error, with an interpolation mode and stats overlay).

**One-off effects (debated).** Google's Manuel Vivo argues the ViewModel "should handle that event immediately causing a state update" and that Channels/SharedFlow for VM events are antipatterns.\[16\]\[17\] Other experienced Kotlin developers (nek12; the Orbit MVI maintainer on Kotlin Slack) counter that "there is no silver bullet here. Both approaches are viable."\[18\]\[19\] **Rule for this project:** anything that must survive rotation (error banner, "file unsupported") goes in **state**. Truly fire-and-forget UI actions (open file picker, haptic) go through a `Channel<PlayerEffect>(Channel.BUFFERED).receiveAsFlow()` collected in `repeatOnLifecycle(STARTED)`.

**Screen file layout (Fragment):** header = binding + VM + collectors; body = `render(state)`; footer = listeners + cleanup. Use ViewBinding (no `findViewById`, no synthetic). Never hold `binding` past `onDestroyView`.

**Resources as your `.scss`/constants:** every user-visible string goes in `strings.xml`; sizes in `dimens.xml`; colors in `colors.xml` referenced only through theme attributes (`?attr/colorPrimary`); styles in `themes.xml`. Hardcoded values in layouts fail Lint in CI. Name resources `<type>_<feature>_<what>`: `fragment_player.xml`, `player_btn_play`, `dimen_player_overlay_padding`.

**Example (illustrative):**
```kotlin
// PlayerContract.kt  (your types.ts)
data class PlayerUiState(
    val phase: Phase = Phase.Idle,
    val interpEnabled: Boolean = true,
    val stats: StatsUi? = null,
    val error: EngineError? = null,
) { enum class Phase { Idle, Loading, Playing, Paused, Ended } }

sealed interface PlayerEvent {
    data class FileChosen(val uri: String) : PlayerEvent
    data object PlayPauseClicked : PlayerEvent
    data class InterpToggled(val enabled: Boolean) : PlayerEvent
    data class EngineChanged(val engine: EngineState) : PlayerEvent   // engine → reducer
    data object ErrorDismissed : PlayerEvent
}
sealed interface PlayerEffect { data object OpenFilePicker : PlayerEffect }

// PlayerReducer.kt  (pure, JVM-testable)
internal object PlayerReducer {
    fun reduce(s: PlayerUiState, e: PlayerEvent): PlayerUiState = when (e) {
        is PlayerEvent.FileChosen       -> s.copy(phase = Phase.Loading, error = null)
        PlayerEvent.PlayPauseClicked    -> s.copy(phase = if (s.phase == Phase.Playing) Phase.Paused else Phase.Playing)
        is PlayerEvent.InterpToggled    -> s.copy(interpEnabled = e.enabled)
        is PlayerEvent.EngineChanged    -> s.fromEngine(e.engine)
        PlayerEvent.ErrorDismissed      -> s.copy(error = null)
    }
}

// PlayerViewModel.kt
class PlayerViewModel(private val engine: PlaybackEngine) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private val _effects = Channel<PlayerEffect>(Channel.BUFFERED)
    val effects: Flow<PlayerEffect> = _effects.receiveAsFlow()

    init { viewModelScope.launch { engine.state.collect { onEvent(PlayerEvent.EngineChanged(it)) } } }

    fun onEvent(e: PlayerEvent) {
        _uiState.update { PlayerReducer.reduce(it, e) }      // 1. state
        when (e) {                                            // 2. side effects (commands to engine)
            is PlayerEvent.FileChosen -> viewModelScope.launch { engine.open(e.uri) }
            PlayerEvent.PlayPauseClicked -> engine.togglePlay()
            is PlayerEvent.InterpToggled -> engine.setInterpolation(e.enabled)
            else -> Unit
        }
    }
}

// PlayerFragment.kt
class PlayerFragment : Fragment(R.layout.fragment_player) {
    // HEADER
    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: PlayerViewModel by viewModels { PlayerViewModel.Factory }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentPlayerBinding.bind(view)
        bindListeners()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.effects.collect(::handleEffect) }
            }
        }
    }
    // BODY
    private fun render(s: PlayerUiState) = with(binding) {
        btnPlay.setText(if (s.phase == Phase.Playing) R.string.player_pause else R.string.player_play)
        switchInterp.isChecked = s.interpEnabled
        overlayStats.isVisible = s.stats != null
    }
    // FOOTER
    private fun bindListeners() = with(binding) {
        btnPlay.setOnClickListener { viewModel.onEvent(PlayerEvent.PlayPauseClicked) }
        switchInterp.setOnCheckedChangeListener { _, on -> viewModel.onEvent(PlayerEvent.InterpToggled(on)) }
    }
    private fun handleEffect(e: PlayerEffect) { /* launch picker */ }
    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
```
**Compose later:** `PlayerContract`, `PlayerReducer` and `PlayerViewModel` stay unchanged. Only `PlayerFragment` is replaced by `PlayerScreen(state, onEvent)` using `collectAsStateWithLifecycle()`. That swap is the payoff for keeping these rules.

### 3.4 Coroutines & Threading

**Decision rule:**

| Work | Mechanism | Why |
|---|---|---|
| UI state, user commands, file open, capability probe | Coroutines (`viewModelScope`, injected dispatchers) | Structured cancellation, testable |
| MediaCodec callbacks | `HandlerThread("FI-Decoder")` + `setCallback(cb, handler)` | Callbacks need a Looper; deterministic ordering |
| EGL/GL, SurfaceTexture, Choreographer | One `HandlerThread("FI-GL")` owning the EGL context | EGL context is current on one thread; SurfaceTexture methods require the owning context current\[20\]\[21\] |
| AudioTrack write / clock | Dedicated thread (`FI-Audio`), elevated priority | Blocking writes, must never stall |
| Telemetry CSV | Single-thread executor or coroutine on an injected IO dispatcher, fed by a lock-free ring buffer | Off the hot path |

**Rules**
- Google's coroutine best practices: "Don't hardcode Dispatchers"; "ViewModel classes should prefer creating coroutines instead of exposing suspend functions"; avoid `GlobalScope` and inject an external scope instead. `viewModelScope` is hardcoded to `Dispatchers.Main`, so tests use `Dispatchers.setMain`.\[2\]\[22\]
- **Don't drive per-frame work with coroutines.** Bridge at the edges instead: engine threads publish `EngineState` into a `MutableStateFlow` (thread-safe), and the UI sends commands that are posted as Handler messages to the engine thread.
- Use thread confinement annotations: `@MainThread`, `@WorkerThread` (androidx.annotation), plus a custom `@GlThread` and `@DecoderThread`. In debug builds, assert with `check(Looper.myLooper() == glLooper)`.
- Cross-thread data: immutable messages, `AtomicLong` for the audio clock, `ArrayBlockingQueue`/ring buffers for frames. No `synchronized` blocks shared between GL and decoder threads.
- Shutdown order is documented and tested: UI → pacer stop → decoder stop/release → GL resources release → EGL terminate → `quitSafely()` threads.
- The engine exposes `PlaybackEngine.release()`. The ViewModel calls it from `onCleared()`. This is your `useEffect` cleanup.

```kotlin
// threading/ThreadAnnotations.kt (illustrative)
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR)
annotation class GlThread

internal class EngineThread(name: String) {
    private val thread = HandlerThread(name, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    val handler = Handler(thread.looper)
    fun assertOn() { if (BuildConfig.DEBUG) check(Looper.myLooper() == thread.looper) { "Wrong thread: ${Thread.currentThread().name}" } }
    fun quit() = thread.quitSafely()
}
```

### 3.5 Media Layer (MediaExtractor / MediaCodec / AudioTrack)

**Purpose:** wrap each platform object in one class that owns its lifecycle, thread and errors.

**Facts the rules are built on (MediaCodec reference):** a codec is in one of three states (Stopped, Executing, Released). Stopped is the conglomeration of Uninitialized, Configured and Error; Executing moves through Flushed, Running and End-of-Stream.\[23\] In async mode the callback must be set before `configure()`. After `flush()` you must call `start()` again to resume, and output-buffer indices from callbacks that were still outstanding at the flush "become invalid… and should be discarded".\[24\]\[25\]

**Rules**
- File set per wrapper: `VideoDecoder.kt` (class), `DecoderState.kt` (sealed), `DecoderConstants.kt`, `DecoderModels.kt` (`DecodedFrame(ptsUs: Micros, …)`).
- State machine as a sealed class, and every public method validates the transition (`check(state is Configured)`).
- Keep a **generation counter**: increment it on `flush()`/seek and drop callbacks carrying an old generation. This handles the "outstanding callbacks after flush" trap.
- Convert to `Micros` the moment a PTS leaves `MediaExtractor.getSampleTime()` or `BufferInfo.presentationTimeUs`. Raw longs never travel further.
- `release()` is idempotent, runs on the owning thread and is called in `finally`. Use `Closeable` + `use {}` for MediaExtractor in short scopes.
- Errors: `onError(CodecException)` maps to `EngineError.CodecFailed(e.diagnosticInfo, e.isRecoverable)`. If `isTransient`, retry. If `isRecoverable`, stop + configure + start. Otherwise release and surface the error to UI state.
- Never leak codecs. Hardware decoder instances are scarce, so the debug build counts live instances in a static counter and asserts it is 0 after `release()`.
- Log tag per class: `FI/Decoder`, `FI/Extractor`, `FI/Audio`.

```kotlin
// decoder/DecoderState.kt + VideoDecoder.kt (illustrative)
internal sealed interface DecoderState {
    data object Uninitialized : DecoderState
    data class Configured(val format: MediaFormat) : DecoderState
    data object Running : DecoderState
    data object EndOfStream : DecoderState
    data class Failed(val error: EngineError) : DecoderState
    data object Released : DecoderState
}

@DecoderThread
internal class VideoDecoder(
    private val thread: EngineThread,
    private val output: Surface,                 // from SurfaceTexture on GL thread
    private val onFrame: (DecodedFrame) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var state: DecoderState = DecoderState.Uninitialized
    private var generation = 0

    fun configure(format: MediaFormat) {
        thread.assertOn(); check(state == DecoderState.Uninitialized)
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        codec = MediaCodec.createDecoderByType(mime).apply {
            setCallback(callback, thread.handler)       // BEFORE configure
            configure(format, output, null, 0)
        }
        state = DecoderState.Configured(format)
    }
    fun start() { thread.assertOn(); codec!!.start(); state = DecoderState.Running }
    fun flush() { thread.assertOn(); generation++; codec?.flush(); codec?.start() } // async: start after flush
    fun release() { thread.assertOn(); runCatching { codec?.stop() }; codec?.release(); codec = null; state = DecoderState.Released }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) { /* feed from Extractor */ }
        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val gen = generation
            onFrame(DecodedFrame(Micros(info.presentationTimeUs), gen))
            c.releaseOutputBuffer(index, /* render = */ true)   // → SurfaceTexture
        }
        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            state = DecoderState.Failed(EngineError.CodecFailed(e.diagnosticInfo, e.isRecoverable))
        }
        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) { /* log + forward */ }
    }
}
```
(The `codec!!` here is acceptable only because `check(state)` guards it. In real code, prefer `requireNotNull(codec)`.)

**AudioTrack:** `AudioClock` reads `AudioTrack.getTimestamp()` → `Micros` and is the master clock. The pacer reads it through an interface (`MasterClock`) so tests can inject a fake.

**API-level guards:** with minSdk 26, `Surface.setFrameRate` and `PowerManager.getThermalHeadroom` (both API 30+) must sit behind `capability/` checks (`Build.VERSION.SDK_INT >= R`) and degrade gracefully.

### 3.6 OpenGL ES / EGL (Kotlin side)

**Facts:** Android's SurfaceTexture doc says "an app can only call its methods while the EGL context that owns the texture is current on the calling thread", and that complex rendering "should use a dedicated thread to isolate the GLES context".\[20\] Romain Guy (Android team) on android-platform: "The EGL context is associated to a single thread."\[21\] Grafika's README warns that "GL/EGL's reliance on thread-local storage doesn't help" with thread safety.\[4\]

**Rules**
- **One GL thread, one `EglCore`, one owner (`GlRenderer`).** Every class in `gl/` is `@GlThread`.
- Resource classes: `EglCore`, `WindowSurface`, `Texture2D`, `ExternalTexture`, `Framebuffer`, `ShaderProgram`, `StorageBuffer`. Each holds its GL handle in a private `var`, offers `release()` (idempotent, zeroes the handle) and throws if used after release.
- Create in `onSurfaceCreated`, recreate on size change, release in reverse creation order before `EglCore.release()`.
- **GL error checks are debug-only:** `glCheck("warp")` calls `glGetError()` when `BuildConfig.GL_CHECKS` is true. It forces a sync, so it never ships in release.
- State reset convention: each pass sets everything it needs (program, viewport, bound textures and images, blend) and unbinds images and SSBOs afterwards. Nothing relies on state left over from a previous pass.
- No GL calls in constructors of objects created on other threads. Build them through `glThread.post { … }`.
- Uniform, attribute and binding numbers come only from `GlBindings.kt` (see §3.7). No `glGetUniformLocation` in the per-frame path.
- Load shaders from `assets/shaders/` once at init, through `ShaderLibrary`, which prepends the generated header.

```kotlin
// gl/ShaderProgram.kt (illustrative)
@GlThread
internal class ShaderProgram private constructor(private var handle: Int, val label: String) {
    fun use() { check(handle != 0) { "$label used after release" }; GLES31.glUseProgram(handle) }
    fun release() { if (handle != 0) { GLES31.glDeleteProgram(handle); handle = 0 } }

    companion object {
        fun compute(label: String, src: String): ShaderProgram =
            link(label, compile(GLES31.GL_COMPUTE_SHADER, src, label))
        fun graphics(label: String, vs: String, fs: String): ShaderProgram =
            link(label, compile(GLES31.GL_VERTEX_SHADER, vs, label), compile(GLES31.GL_FRAGMENT_SHADER, fs, label))

        private fun compile(type: Int, src: String, label: String): Int {
            val s = GLES31.glCreateShader(type)
            GLES31.glShaderSource(s, src); GLES31.glCompileShader(s)
            val ok = IntArray(1); GLES31.glGetShaderiv(s, GLES31.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { val log = GLES31.glGetShaderInfoLog(s); GLES31.glDeleteShader(s); error("[$label] compile: $log") }
            return s
        }
        private fun link(label: String, vararg shaders: Int): ShaderProgram { /* attach, link, check, delete shaders */ TODO() }
    }
}
```

### 3.7 GLSL Shader Rules

**Files:** `assets/shaders/<pass>.<stage>` with stage ∈ `vert|frag|comp` (glslang recognizes these extensions, which makes offline validation in CI trivial). Use one `main()` per file. Shared snippets go in `common/*.glsl`. Blender's GLSL style guide uses the same "one and only one main()" rule and module-prefixed names.\[26\]

**Mandatory layout (the header/body/footer of a shader):**
1. **Header:** `#version 310 es` (must be line 1, so the loader inserts generated defines *after* it), `#extension` lines, the generated `// <defines from Kotlin>` block, `precision` statements, and a doc comment with inputs, outputs, bindings, dispatch size and debug modes.
2. **Interface:** every `in/out/uniform/buffer/image` with explicit `layout(location = …)` / `layout(binding = …)`. The Khronos OpenGL wiki notes that the same uniform in two programs is *not* guaranteed the same location unless you specify it.\[27\] GLSL ES 3.10 supports explicit uniform locations.
3. **Body:** small pure helper functions, then `main()`.
4. **Footer:** debug-view switch (`#if DEBUG_VIEW == …`), then the final write.

**Naming:** `a_` vertex attributes, `v_` varyings, `u_` uniforms, `o_` fragment outputs. Anton Gerdelan's OpenGL tutorials use exactly this scheme,\[28\] and The Book of Shaders uses `u_`.\[29\] Samplers and images: `u_srcTex`, `u_mvImage`. SSBO blocks: `MotionBuf` with instance `b_motion`. Use `snake_case` for local helper names only if your team prefers; pick one and lint it.

**Precision:** Arm's Mali guidance says "in most cases, mediump (16-bit precision) is sufficient". Arm's Community blog post "Accelerating Mali GPU analysis using Arm Mobile Studio" adds that "switching from 32-bit highp to 16-bit mediump varyings will halve interpolator requirements, and this often causes knock-on improvements in shader logic". Arm also recommends mediump samplers. The same Arm material warns that texture coordinates need highp interpolation for precision.\[30\] Ralph Potter (Khronos Standards, Samsung) adds in his Vulkanised 2023 talk "Battle-tested Optimisations for Mobile": "Beware of compilers promoting precision. Test on lots of devices to catch shader precision artefacts early." **Rule:** use `precision mediump float;` by default. Use `highp` for UVs, pixel coordinates, motion vectors, SAD accumulators and anything compared against thresholds.

**Compute:** workgroup size comes from Kotlin constants (e.g. 8×8 = 64 invocations, below the ES 3.1 guaranteed minimum of 128). Never hardcode it in both places.

**External frames:** `samplerExternalOES` (via `GL_OES_EGL_image_external_essl3`) is used **only** in `oes_to_luma.frag`, which copies the decoder frame into regular `R8`/`RGBA8` textures. Every compute pass reads normal textures. Treat compute-shader access to external textures as unsupported unless you have proven it on your target devices.

**Keeping Kotlin and GLSL in sync:** Kotlin is the single source of truth. `GlBindings.kt` defines the numbers, and `ShaderLibrary` generates a `#define` block from them and inserts it after `#version`. GLSL ES has no `#include`, so the loader also expands `#include "common/x.glsl"` lines. An instrumented test compiles every shader on device and fails on any log output.

```kotlin
// gl/GlBindings.kt (illustrative)
internal object GlBindings {
    const val LOC_A_POSITION = 0
    const val LOC_U_T_PHASE = 0
    const val BIND_TEX_PREV = 0
    const val BIND_TEX_NEXT = 1
    const val BIND_IMG_MOTION = 2
    const val WG_X = 8
    const val WG_Y = 8
    const val BLOCK_SIZE = 16
    fun defines(debugView: Int): String = """
        #define BIND_TEX_PREV $BIND_TEX_PREV
        #define BIND_TEX_NEXT $BIND_TEX_NEXT
        #define BIND_IMG_MOTION $BIND_IMG_MOTION
        #define LOC_U_T_PHASE $LOC_U_T_PHASE
        #define WG_X $WG_X
        #define WG_Y $WG_Y
        #define BLOCK_SIZE $BLOCK_SIZE
        #define DEBUG_VIEW $debugView
    """.trimIndent()
}
```

```glsl
// assets/shaders/warp.frag  (ILLUSTRATIVE EXAMPLE)
#version 310 es
// <generated defines inserted here by ShaderLibrary>
precision mediump float;
precision highp int;
/*
 * warp.frag — motion-compensated interpolation of frame at phase t in [0,1].
 * IN : u_prevTex (binding BIND_TEX_PREV), u_nextTex (BIND_TEX_NEXT), u_mvTex (motion, px), v_uv
 * OUT: o_color (RGBA8)
 * DEBUG_VIEW: 0=off 1=motion vectors 2=confidence
 */
layout(binding = BIND_TEX_PREV) uniform mediump sampler2D u_prevTex;
layout(binding = BIND_TEX_NEXT) uniform mediump sampler2D u_nextTex;
layout(binding = BIND_IMG_MOTION) uniform highp sampler2D u_mvTex;   // highp: vectors
layout(location = LOC_U_T_PHASE) uniform highp float u_tPhase;

in highp vec2 v_uv;                                                  // highp: coordinates
layout(location = 0) out vec4 o_color;

highp vec2 texelToUv(highp vec2 px) { return px / vec2(textureSize(u_prevTex, 0)); }

void main() {
    highp vec2 mv = texture(u_mvTex, v_uv).xy;
    vec4 a = texture(u_prevTex, v_uv - texelToUv(mv) * u_tPhase);
    vec4 b = texture(u_nextTex, v_uv + texelToUv(mv) * (1.0 - u_tPhase));
    vec4 color = mix(a, b, u_tPhase);
#if DEBUG_VIEW == 1
    color = vec4(0.5 + mv / 32.0, 0.0, 1.0);
#endif
    o_color = color;
}
```

### 3.8 Gradle / Build Rules

- **Version catalog:** `[versions]` keys are camelCase (`agp`, `kotlin`, `coroutines`). `[libraries]` aliases are kebab-case, grouped by prefix (`androidx-lifecycle-viewmodel-ktx`, `kotlinx-coroutines-android`). `[plugins]` hold both third-party and your convention plugins (`fi-android-library`). No version string ever appears in a `build.gradle.kts`.
- The **toolchain pins** from your Phase 1 plan (AGP 9.4.x, Gradle 9.6+, Kotlin 2.4.20, compileSdk 37, minSdk 26) live only in the catalog and `build-logic`. Recheck the AGP ↔ Gradle ↔ Kotlin compatibility table whenever you bump any of them.
- **Build types:** `debug` sets `buildConfigField("boolean","GL_CHECKS","true")`, `DEBUG_OVERLAY=true` and `TRACE_VERBOSE=true`, and enables StrictMode. `release` turns all three off and enables R8. Add a `benchmark` build type (release-like, debuggable=false, signed with debug keys) for Macrobenchmark.
- `BuildConfig` is generated per module only where needed (`buildFeatures { buildConfig = true }` in `:engine:impl` and `:app`).
- **Quality gates as tasks:** `ktlintCheck`, `detekt`, `lint`, `testDebugUnitTest`, and a custom `validateShaders` (runs `glslangValidator` over `assets/shaders` with the generated defines).
- **CI (GitHub Actions, free tier):** run on every PR: checkout → setup JDK → `gradle/actions/setup-gradle` (cache) → `./gradlew ktlintCheck detekt lint testDebugUnitTest validateShaders assembleDebug`. Instrumented GL and codec tests run locally on a real device, because emulator GPUs aren't representative.

```toml
# gradle/libs.versions.toml (illustrative excerpt — pin versions per your plan)
[versions]
agp = "9.4.0"
kotlin = "2.4.20"
coroutines = "x.y.z"
lifecycle = "x.y.z"

[libraries]
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
androidx-lifecycle-viewmodel-ktx = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "lifecycle" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
fi-android-library = { id = "fi.android.library" }   # from build-logic
```

### 3.9 Testing Rules

| What | Where | Tools | Rule |
|---|---|---|---|
| Reducer, pacer phase `t` math, scene-cut thresholds, unit conversions | `src/test` (JVM) | JUnit, kotlinx-coroutines-test `runTest`, Turbine for Flow | Must run in <5 s total; no Android imports needed (that's why `:engine:api` is pure Kotlin) |
| ViewModel | `src/test` | `Dispatchers.setMain(StandardTestDispatcher())`, fake `PlaybackEngine` | Assert state sequence with Turbine |
| Decoder lifecycle, flush/seek, release leaks | `src/androidTest` | AndroidJUnit4 + test clip in `androidTest/assets` | Real device; assert live-codec counter == 0 |
| Shaders | `src/androidTest` | Offscreen EGL pbuffer → render → `glReadPixels` → compare to golden PNG (PSNR / max-abs-diff tolerance) | Golden images versioned per GPU family if needed |
| Frame pacing / jank | `:benchmark` module | Macrobenchmark + `FrameTimingMetric`; Microbenchmark for CPU hot code | Run before/after any pacer change |

Naming: `fun reduce_playPause_whenPlaying_pauses()` or backtick names (`` `play pause while playing pauses` ``). Pick one style per module.

```kotlin
// PacerMathTest.kt (illustrative)
class PacerMathTest {
    @Test fun `phase is half-way at 60 on 30 fps source`() {
        val t = PacerMath.phase(src0 = Micros(0), src1 = Micros(33_333), display = Micros(16_667))
        assertEquals(0.5f, t, 1e-3f)
    }
}
```

### 3.10 Logging, Telemetry & Tracing

- **Log tags:** `FI/<Module>` as a file-private `const val TAG`. Wrap logging in a tiny `Log` facade (`FiLog.d(TAG) { "msg $x" }`) whose lambda is only evaluated when `BuildConfig.DEBUG`. Never build strings per frame in release.
- **Trace sections:** Android's `Trace.beginSection` name "may be at most 127 Unicode code units long" (UTF-16 units, not characters) and throws `IllegalArgumentException` if too long.\[5\] Use `androidx.tracing.Trace` (TraceCompat is deprecated in its favor).\[31\] Names are constants in `TraceNames.kt`: `FI:extract`, `FI:decode:out`, `FI:gl:oes2luma`, `FI:interp:me`, `FI:interp:warp`, `FI:pacer:vsync`, `FI:audio:write`. Always use `trace(NAME) { … }` (try/finally) so sections can't be left unbalanced.
- Use async sections and counters for frame lifecycles and queue depths (`FI:q:decoded`), viewed in Perfetto.\[32\]
- **Telemetry CSV:** the first line is a schema version comment (`# fi-telemetry v1`), then a fixed header:
  `frame_idx,src_pts_us,vsync_ns,present_ns,t_phase,mode,me_gpu_us,warp_gpu_us,confidence,scene_cut,thermal_headroom,dropped`
  `mode ∈ {REAL,INTERP,REPEAT}`. Rows go into a preallocated ring buffer on the GL thread and are flushed by the telemetry thread. Adding a column means bumping the schema version.

### 3.11 Native C++ / NDK & CMake (later phases)

- **Layout:** `engine/native/src/main/cpp/{CMakeLists.txt, jni/, core/, include/fi/}`. `jni/` holds only `*_jni.cpp` bridge files. `core/` is pure C++ with no `jni.h` include, so it can be unit-tested and reused on iOS.
- **JNI boundary (Android "JNI tips"):** "Minimize the number of threads that need to touch or be touched by JNI"; "Keep your interface code in a low number of easily identified C++ and Java source locations".\[33\] A JNIEnv can't be shared between threads. Natively attached threads must delete their local refs manually and must call `DetachCurrentThread`. Never compare references with `==`.\[34\]\[35\]
- Keep a native handle as a `Long` inside a Kotlin `Closeable` (`NativeInterp`). `close()` calls `nativeDestroy(handle)` exactly once.
- **C++ rules:** C++17/20, RAII for every GL, Vulkan or ncnn object (`unique_ptr` with custom deleters), no raw `new`/`delete`, no exceptions crossing JNI, `.h` = declarations with include guards or `#pragma once`, `.cpp` = definitions. Order inside a file: includes → namespace → constants → types → functions.
- Build with CMake via AGP `externalNativeBuild`. ABI filters `arm64-v8a` (plus `x86_64` for emulator only). Pin the NDK version in the catalog or `build-logic`.

### 3.12 Docs & Git Rules

- **README per module:** purpose, public API, owning thread(s), dependencies allowed, how to test.
- **ADRs** in `docs/adr/NNNN-title.md` (Context → Decision → Status → Consequences). The first ADRs to write are: single GL thread; api/impl split instead of 9 modules; Views-first UI; Kotlin-generated shader defines; audio as master clock.
- **Commits:** Conventional Commits `type(scope): summary`. Types are `feat|fix|perf|refactor|test|docs|build|ci|chore`, and scope = module or package (`decoder`, `gl`, `interp`, `pacer`, `player`). `BREAKING CHANGE:` in the footer marks `:engine:api` contract changes.
- **Branches:** `feat/interp-scene-cut`, `fix/decoder-flush-race`, `spike/ncnn-rife`.
- **PR checklist:** ☐ no new `android.*` import in `:engine:api` ☐ new resources released and leak-checked ☐ thread annotation on new classes ☐ no per-frame allocation ☐ trace sections added ☐ unit/golden tests updated ☐ CSV schema bumped if columns changed ☐ ADR if a decision changed.

### 3.13 iOS Swift / Metal (later, brief)

- One folder per feature: `PlayerView.swift`, `PlayerViewModel.swift` (`@Observable`/`ObservableObject`), `PlayerState.swift` (struct + `enum PlayerAction` + `static func reduce`), `PlayerConstants.swift`. This is the same reducer contract as Android, so `PlayerReducer` tests can be ported almost line-by-line.
- The engine is a Swift package (`FIEngine`) with no SwiftUI import, mirroring `:engine:api`/`:engine:impl`.
- Metal: `.metal` files per pass (`warp.metal`), with a shared C header (`ShaderTypes.h`) included by both Swift (bridging header) and Metal as the single source of truth for buffer indices and structs. That header plays the role `GlBindings.kt` plays on Android.
- Use `half` the way you use `mediump`, and `float` for coordinates and motion vectors. Wrap `MTLTexture`/`MTLBuffer` lifetimes in owner classes and give the command queue a single owner.

---

## 4. Community Consensus vs Debated Topics

| Topic | Consensus | Debate | **Choice for this project** |
|---|---|---|---|
| UDF + single immutable UI state | Strong (Google "Strongly recommended"; most blogs) | — | Adopt\[15\] |
| MVVM vs MVI | Both converge on ViewModel + StateFlow | MVI "Overkill for Simple UIs" (CodeElevation) vs fewer inconsistent states (DZone) | Pragmatic MVI (reducer, no framework) |
| One-off events | — | Google: reduce to state; nek12/Orbit: Channels are fine | Persistent → state; fire-and-forget → `Channel.receiveAsFlow()` |
| Multi-module for small apps | Boundaries help; over-modularization is a real trap | NiA showcases 30+ modules; solo devs often say packages suffice | api/impl + packages; split later on triggers\[36\] |
| Clean Architecture use-case layer | Google: domain layer is "optional", "Recommended in big apps" | Many blogs: mandatory; GalwayBus README: "it can be argued that full use of Clean Architecture abstractions is overkill" | **No use-case layer.** ViewModel → `PlaybackEngine` interface directly\[15\]\[37\]\[38\] |
| Coroutines in hot media paths | Coroutines for orchestration | Some wrap codec callbacks in `callbackFlow` | HandlerThreads for codec/GL/audio; Flow only at the state boundary |
| DI framework | Inject dependencies | Hilt vs manual DI | Manual `AppContainer` now; Hilt only if module count grows |
| Shader naming | Prefixes widespread (`u_` etc.) | Naming varies by engine/style guide (Blender uses snake_case + suffixes) | `a_/v_/u_/o_` + explicit layouts |

---

## 5. Caveats

- **Reddit/LinkedIn sourcing:** I couldn't retrieve Reddit threads or LinkedIn posts directly, so I don't quote them. Community opinion here comes from public developer posts on dev.to, Medium, Kotlin Slack archives, GitHub READMEs and Habr, which carry the same debates. Treat those as practitioner opinion, not authority. The *official* rules (architecture, coroutines, modularization, MediaCodec, JNI, Trace limits) come from Google and Kotlin documentation.
- **Grafika** is a primary reference for threading and GL patterns, but its own README says it is "not intended as a demonstration of the proper way to do things", and GitHub shows the repository "was archived by the owner on Apr 15, 2025. It is now read-only." Borrow its threading discipline, not its code structure.
- **Toolchain versions** (AGP 9.4.x, Kotlin 2.4.20, compileSdk 37) come from your Phase 1 plan; I didn't verify them here. Confirm compatibility when you create the catalog.
- **GPU behavior varies.** Precision promotion, external-texture support in compute, and workgroup limits differ across Adreno, Mali and PowerVR. The rules above are deliberately conservative, and golden-image tests on at least two GPU vendors are the real safety net.
- All code blocks are **illustrative examples**, written to show structure. They aren't production-complete; for instance, `link()` and the extractor feeding are stubbed.

## Sources

1. [Common modularization patterns](https://developer.android.com/topic/modularization/patterns)
2. [Best practices for coroutines in Android](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
3. [Kotlin Coroutines in Android Summary](https://proandroiddev.com/kotlin-coroutines-in-android-summary-1ed3048f11c3?gi=b91da704f273)
4. [GitHub - google/grafika: Grafika test app](https://github.com/google/grafika)
5. [Trace](https://developer.android.com/reference/android/os/Trace)
6. [github.com](https://github.com/saldisobi/nowinandroid/blob/main/docs/ModularizationLearningJourney.md)
7. [nowinandroid/docs/ModularizationLearningJourney.md at main · android/nowinandroid](https://github.com/android/nowinandroid/blob/main/docs/ModularizationLearningJourney.md)
8. [The build-logic](https://github.com/android/nowinandroid/blob/main/build-logic/README.md)
9. [Coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
10. [Coding Conventions - Kotlin Programming Language](https://kotlinlang.org/docs/reference/coding-conventions.html)
11. [Class layout · Issue #5 · Kotlin/kotlin-style-guide](https://github.com/Kotlin/kotlin-style-guide/issues/5)
12. [Use explicit API mode - Simplicity](https://kotlinlang.org/docs/api-guidelines-simplicity.html)
13. [Library API guidelines](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/sparse-8718359-L54500000955100181/docs/api_guidelines.md)
14. [Explicit API mode for Kotlin/Android](https://chao2zhang.medium.com/explicit-api-mode-for-kotlin-on-android-b8264fdd76d1)
15. [Recommendations for Android architecture](https://developer.android.com/topic/architecture/recommendations)
16. [ViewModel: One-off event antipatterns](https://medium.com/androiddevelopers/viewmodel-one-off-event-antipatterns-16a1da869b95)
17. [Manuel Vivo on X: "We often get asked why we don't recommend exposing events originated from ViewModels with Channels or SharedFlow 🧐 In this post, we explain the reasons why that's not a good idea along with other related antipatterns 🌵 Hope this clarifies your doubts! https://t.co/3kQ6z7PQJt" / X](https://x.com/manuelvicnt/status/1532324889255940098)
18. [\<https medium com androiddevelopers viewmodel one off event kotlinlang #orbit-mvi](https://slack-chats.kotlinlang.org/t/516180/https-medium-com-androiddevelopers-viewmodel-one-off-event-a)
19. [Events as State are an Antipattern in MVI and MVVM - here's why](https://nek12.dev/blog/en/events-as-state-are-an-antipattern-in-mvi-and-mvvm-heres-why/)
20. [SurfaceTexture](https://source.android.com/docs/core/graphics/arch-st)
21. [Sharing OpenGL textures across multiple threads](https://groups.google.com/g/android-platform/c/qZMe9hpWSMU/m/lllfBTh6RdEJ)
22. [Best practices for coroutines in Android - JOYK Joy of Geek, Geek News, Link all geek](https://www.joyk.com/dig/detail/1615718688593684)
23. [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
24. [MediaCodec - Android SDK](http://docs.52im.net/extend/docs/api/android-50/reference/android/media/MediaCodec.html)
25. [MediaCodec.Flush Method (Android.Media)](https://learn.microsoft.com/en-us/dotnet/api/android.media.mediacodec.flush?view=net-android-34.0)
26. [GLSL Style Guide - Blender Developer Documentation](https://developer.blender.org/docs/handbook/guidelines/glsl/)
27. [Uniform (GLSL) - OpenGL Wiki](<https://www.khronos.org/opengl/wiki/Uniform_(GLSL)>)
28. [Shaders - Anton's OpenGL 4 Tutorials](https://antongerdelan.net/opengl/shaders.html)
29. [The Book of Shaders: uniforms](https://thebookofshaders.com/03/)
30. [Document Number: ARM-ECM-0442477 Arm Non-Confidential Version: 1.0 Page 1](https://documentation-service.arm.com/static/5eff5d5edbdee951c1cd5f20)
31. [TraceCompat](https://developer.android.com/reference/androidx/core/os/TraceCompat)
32. [Profile types and when each is helpful](https://developer.android.com/topic/performance/tracing/profile-types-overview)
33. [JNI tips](https://developer.android.com/training/articles/perf-jni)
34. [JNI Tips](https://stuff.mit.edu/afs/sipb/project/android/docs/training/articles/perf-jni.html)
35. [perf jni](https://stuff.mit.edu/afs/sipb.mit.edu/project/android/docs/training/articles/perf-jni.html)
36. [DEV Community](https://dev.to/lucy1/the-future-of-android-app-architecture-with-modularization-and-multi-module-projects-1l01)
37. [This was created as part of effort to get more familiar with developing Android apps using](https://github.com/joreilly/GalwayBus/blob/6bf44d4859b53018e31fd7dd7bae971bd837cc04/README.md)
38. [Guide to app architecture](https://developer.android.com/topic/architecture)
