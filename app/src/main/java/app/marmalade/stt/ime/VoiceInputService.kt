package app.marmalade.stt.ime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.marmalade.stt.MainActivity
import app.marmalade.stt.data.AppearanceSettings
import app.marmalade.stt.data.SpeechPrefs
import app.marmalade.stt.data.SpeechSettings
import app.marmalade.stt.data.TextProcSettings
import app.marmalade.stt.speech.EngineHold
import app.marmalade.stt.speech.ListenSurface
import app.marmalade.stt.speech.ListenTiming
import app.marmalade.stt.speech.LivePipeline
import app.marmalade.stt.speech.SpeechEngineManager
import app.marmalade.stt.speech.SttErrorKind
import app.marmalade.stt.speech.SttEvent
import app.marmalade.stt.ui.theme.Appearance
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   FUTO Keyboard (or any keyboard's mic key) switches to our voice subtype
//     │
//     ▼
//   onStartInputView ── mic permission? model installed? ──no──► blocker
//     │                  (Allow → MicPermissionActivity, Open app → MainActivity;
//     │                   re-checked the next time the panel is shown)
//     yes → startListening(): SpeechEngineManager.listen(KEYBOARD) in the service scope
//     │   (Settings → End of speech → Voice keyboard, #43: VoiceIme.configured)
//     │   Partial → LivePipeline cleanPartial → ImeTextWriter.showPartial (composing region) + panel
//     │   Final   → ImeTextWriter.commitFinal: LivePipeline → TextEdit
//     │             → deleteSurroundingText + commitText
//     │   other   → VoiceIme.reduce → panel
//     │
//     ├── mic tap: running → engines.stop() (Final lands, stay here; unlocks);
//     │            idle → start
//     ├── mic press-and-hold (#43): idle → start locked; running in an auto
//     │            mode → lock (VoiceIme.locking); Tap to stop → just a tap
//     ├── Done:    running → engines.stop(), switch back once the flow ends;
//     │            idle → switch back now
//     ├── a committed Final in an auto mode (Smart Turn, Pause, End word),
//     │            unlocked → same as Done (VoiceIme.actsAsDone)
//     ├── Cancel:  abort (no Final), drop the partial, switch back
//     ├── 30 s without speech (IdleTimeout) → engines.stop(): pause, stay here
//     │            (auto modes, unlocked: VoiceIme.idlePauses)
//     ├── panel hidden / input finished / service destroyed → abort
//     └── panel hidden without Done or Cancel (Back, Home) → also switch back
//
//   While the panel is shown (no blocker) it holds the engine
//   (SpeechEngineManager.hold, #78): a second mic tap doesn't reload the model.
//   Input finished / panel hidden / service destroyed closes the hold.
//
//   The keyboard never holds the mic while it isn't shown. Audio and text stay
//   in memory (ARCHITECTURE.md D10).
// -----------------------------------------------------------------------------

/**
 * The voice keyboard (ARCHITECTURE.md D6). Listens while its panel is shown
 * and writes what it hears into the focused editor, then hands the user back
 * to their keyboard with `switchToPreviousInputMethod()`.
 *
 * An input method isn't an activity, so to host Compose it is its own
 * [LifecycleOwner] (RESUMED while the panel is shown), [SavedStateRegistryOwner]
 * and [ViewModelStoreOwner].
 */
@AndroidEntryPoint
class VoiceInputService :
    InputMethodService(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    @Inject lateinit var engines: SpeechEngineManager

    @Inject lateinit var textProcSettings: TextProcSettings

    @Inject lateinit var speechSettings: SpeechSettings

    @Inject lateinit var appearanceSettings: AppearanceSettings

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var pipeline: LivePipeline

    /**
     * Settings → Appearance, read from onCreate so the panel opens in it;
     * null until the read returns, and the panel draws nothing until then.
     */
    private lateinit var appearance: StateFlow<Appearance?>

    private val writer = ImeTextWriter { currentInputConnection?.asEditorConnection() }.apply {
        onEditorFailure = { operation -> Log.w(TAG, "Editor request failed: $operation") }
    }
    private val _state = MutableStateFlow(VoiceImeState())

    /** The panel state without the level, so level updates don't recompose the text. */
    private val panelState: StateFlow<VoiceImeState> = _state
        .map { it.copy(level = 0f) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, _state.value)

    private val level: StateFlow<Float> = _state
        .map { it.level }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, 0f)

    private var listenJob: Job? = null

    /** Keeps the model loaded between this panel's sessions while it is shown (#78). */
    private var engineHold: EngineHold? = null

    /**
     * The panel is about to hide for a reason that must not hand the keyboard
     * back: switchBack() already did, or we opened our own activity (the mic
     * prompt, the app), after which the user returns to this panel.
     */
    private var keepOnHide = false

    /** Bumped by every start and abort, so a dropped session's cleanup can't touch its successor. */
    private var sessionId = 0

    override fun onCreate() {
        super.onCreate() // Hilt injects here
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        pipeline = LivePipeline(scope, textProcSettings)
        appearance = appearanceSettings.appearance.stateIn(scope, SharingStarted.Eagerly, null)
    }

    override fun onCreateInputView(): View {
        // Compose finds its owners on the window's root view.
        window.window!!.decorView.setViewTreeOwners()
        return ComposeView(this).apply {
            setViewTreeOwners()
            setContent {
                val state by panelState.collectAsStateWithLifecycle()
                // Compose State, not the StateFlow's value: the ring's
                // snapshotFlow only sees changes to snapshot state.
                val levelState = level.collectAsStateWithLifecycle()
                // Nothing until Settings → Appearance is read (milliseconds), so a cold start never flashes the default theme.
                val panelAppearance = appearance.collectAsStateWithLifecycle().value ?: return@setContent
                InAppLanguage {
                    VoiceImePanel(
                        state = state,
                        appearance = panelAppearance,
                        level = { levelState.value },
                        onCancel = ::onCancel,
                        onMic = ::onMic,
                        onMicLongPress = ::onMicLongPress,
                        onDone = ::onDone,
                        onAllowMic = { startActivityInNewTask(MicPermissionActivity::class.java) },
                        onOpenApp = { startActivityInNewTask(MainActivity::class.java) },
                    )
                }
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keepOnHide = false
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        writer.usesComposing = info.inputType != InputType.TYPE_NULL
        // Either may have changed while the panel was away (permission
        // granted, model downloaded or removed).
        val blocker = when {
            !hasMicPermission() -> VoiceImeBlocker.NO_MIC_PERMISSION
            !engines.isReady() -> VoiceImeBlocker.NO_MODEL
            else -> null
        }
        _state.update { VoiceIme.blocked(it, blocker) }
        if (blocker != null) {
            abort()
            closeEngineHold()
            return
        }
        if (engineHold == null) engineHold = engines.hold(preload = false) // the listen below loads
        if (!restarting) {
            // The user switched to us to talk. A restart (the app reset the
            // same field) keeps whatever is running, or stays paused.
            startListening()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Log.d(TAG, "Input view finishing; finishingInput=$finishingInput")
        abort()
        closeEngineHold()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        Log.d(TAG, "Input window hidden")
        abort()
        closeEngineHold()
        // Hidden without Done or Cancel (Back, Home, the app closed the
        // keyboard): hand the user's keyboard back too, or the next text
        // field would open this panel and the mic instead of their keyboard.
        if (!keepOnHide) switchBack()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        abort()
        closeEngineHold()
        scope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }

    private fun onCancel() {
        abort()
        switchBack()
    }

    private fun onMic() {
        val phase = _state.value.phase
        when {
            phase == VoiceImePhase.FINISHING -> Unit
            phase.isActive -> stop(exitAfter = false)
            _state.value.blocker == null -> startListening()
        }
    }

    /** Press-and-hold (#43); the panel offers it only while [VoiceImeState.canLock]. */
    private fun onMicLongPress() {
        val state = _state.value
        when {
            !state.canLock -> onMic()
            state.phase.isActive -> _state.update { VoiceIme.locking(it) }
            else -> startListening(locked = true)
        }
    }

    private fun onDone() {
        Log.d(TAG, "Done requested; sessionActive=${listenJob?.isActive == true}")
        if (listenJob?.isActive == true) stop(exitAfter = true) else switchBack()
    }

    /** [locked]: started by pressing and holding the mic (#43). */
    private fun startListening(locked: Boolean = false) {
        if (listenJob?.isActive == true) return
        val requestedAt = SystemClock.elapsedRealtime()
        val session = ++sessionId
        // An aborted session may still be closing the mic; the engine would answer BUSY.
        val previous = listenJob
        val modelLoaded = engines.engineOrNull()?.isLoaded == true
        _state.update { VoiceIme.started(it, modelLoaded, locked) }
        listenJob = scope.launch {
            previous?.join()
            // The engine reads the same setting for this session (SpeechEngineManager.listen).
            val end = try {
                speechSettings.current().endFor(ListenSurface.KEYBOARD)
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't read the speech settings", e)
                SpeechPrefs().keyboardEnd
            }
            _state.update { VoiceIme.configured(it, end) }
            // Nobody speaking for a while, in an auto mode and unlocked: pause (graceful, the panel stays up).
            val idle = IdleTimeout(this) { if (VoiceIme.idlePauses(_state.value)) stop(exitAfter = false) }
            try {
                engines.listen(ListenSurface.KEYBOARD, requestedAt = requestedAt)
                    // Settings read, file probe and releasing the model all stay off the main thread.
                    .flowOn(Dispatchers.Default)
                    .collect { event ->
                        idle.onEvent(event)
                        val committed = onEvent(event)
                        // The first end in an auto mode, once its text is in: as if the user tapped Done.
                        if (VoiceIme.actsAsDone(_state.value, event, committed)) stop(exitAfter = true)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The engine reports its own failures as Error events; this is anything else.
                Log.e(TAG, "Listening failed", e)
                onEvent(SttEvent.Error(SttErrorKind.RECOGNITION, e.message ?: "Listening failed"))
            } finally {
                idle.cancel()
                if (session == sessionId) {
                    writer.clearPartial()
                    val (next, switchBack) = VoiceIme.ended(_state.value)
                    _state.value = next
                    if (switchBack) switchBack()
                }
            }
        }
    }

    /** Returns whether a Final put text in the editor. */
    private suspend fun onEvent(raw: SttEvent): Boolean {
        // Cleaned once, so the panel and the editor show the same partial ("" clears both).
        val event = if (raw is SttEvent.Partial) SttEvent.Partial(pipeline.get().cleanPartial(raw.text)) else raw
        var committed = false
        when (event) {
            is SttEvent.Partial -> writer.showPartial(event.text)
            is SttEvent.Final -> {
                val startedAt = SystemClock.elapsedRealtime()
                committed = writer.commitFinal(event.text, event.nearSilent, pipeline.get(), event.silent)
                Log.d(TAG, "Final processed; editRequestsAccepted=$committed")
                ListenTiming.delivered("keyboard", startedAt)
            }
            is SttEvent.NoSpeech, is SttEvent.Error -> writer.clearPartial()
            else -> Unit
        }
        _state.update { VoiceIme.reduce(it, event) }
        return committed
    }

    /** Finish gracefully: the Final still lands. [exitAfter]: then switch back (Done). */
    private fun stop(exitAfter: Boolean) {
        _state.update { VoiceIme.stopping(it, exitAfter) }
        engines.stop()
    }

    /** Drop the session at once, without a Final, and take its partial out of the editor. */
    private fun abort() {
        sessionId++
        listenJob?.cancel()
        writer.clearPartial()
        _state.update { VoiceIme.cancelled(it) }
    }

    /** The panel went away: the model may be freed (after the aborted session ends). */
    private fun closeEngineHold() {
        engineHold?.close()
        engineHold = null
    }

    private fun switchBack() {
        Log.d(TAG, "Switching to previous input method")
        keepOnHide = true
        if (!switchToPreviousInputMethod()) requestHideSelf(0)
    }

    /** An input method has no activity task of its own. */
    private fun startActivityInNewTask(activity: Class<out Activity>) {
        keepOnHide = true
        startActivity(Intent(this, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun View.setViewTreeOwners() {
        setViewTreeLifecycleOwner(this@VoiceInputService)
        setViewTreeSavedStateRegistryOwner(this@VoiceInputService)
        setViewTreeViewModelStoreOwner(this@VoiceInputService)
    }

    private companion object {
        const val TAG = "VoiceInputService"
    }
}
