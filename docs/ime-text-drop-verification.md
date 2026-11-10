# Verify dictation finalization in Gemini

This change removes explicit composing-text deletion from ordinary finalization.
Android documents that [`commitText()` replaces the current composing text](https://developer.android.com/reference/android/view/inputmethod/InputConnection#commitText(java.lang.CharSequence,int)).
The change does not establish how Gemini stores drafts. A host may accept an editor request
and still clear its own state later; a successful return is not persistence proof.

## Automated checks

With JDK 17 and the Android SDK configured:

```sh
./gradlew :app:testFdroidDebugUnitTest --tests 'app.marmalade.stt.ime.ImeTextWriter*'
```

Tests observe every individual edit, including edits inside a batch. Ordinary
finalization must replace the partial directly, without an intermediate deletion.
Other cases cover deferred edits, truncated/rejected partials, rejected final
requests, cleanup after rejection, cancellation, and editing commands. The tests
make no assumption that blank text invalidates an editor connection. Separate
Robolectric checks exercise a real Android `EditText` and its `InputConnection`,
including text-watcher observations, composing spans, commands, and cancellation.
These checks do not exercise Gemini or a real keyboard switch.

## Device comparison

Use the same phone, Android version, Gemini version, speech model, and settings.
Record the exact commit and APK version for each build. First reproduce with the
original build, then install the candidate and repeat the same actions. A run
where the original failure cannot be reproduced is inconclusive.

1. Open Gemini's main input and start the Marmalade voice keyboard.
2. Dictate a fixed phrase, such as "Hello world", and wait for a visible partial.
3. Tap Done. Check the full text after the previous keyboard returns and again
   after several seconds; confirm it is present when sending the prompt.
4. Repeat with these initial values: empty, one space, `a`, and `.`. Reset the
   field between trials. Repeat each case five times.
5. Repeat with automatic completion. Separately, use the mic button to finish
   while keeping the voice panel open, then tap Done. This separates final
   replacement from keyboard teardown.
6. Smoke-test a standard Android editor, Cancel, a filtered/no-speech result,
   multiple utterances, "scratch that", and spoken punctuation after a space.

Capture the diagnostic events during each trial:

```sh
adb logcat -v threadtime -s VoiceInputService:D
```

New diagnostics contain operation names and lifecycle flags, not dictated text.
"Final processed; editRequestsAccepted=true" means only that the writer's editor
requests returned success. A false result may mean a filtered/no-op final; a
separate "Editor request failed" warning identifies rejected requests.

If a trial fails, record whether the field clears before or after the previous
keyboard appears, plus the corresponding Done, final, failure, input-view-finish,
window-hidden, and keyboard-switch events. No final event suggests the session
ended without delivering one. A rejected request points toward a stale connection.
Accepted requests followed by a wipe still require investigation of host state or
teardown; do not add an arbitrary delay or claim batching guarantees persistence.

Only report the original reproduction fixed after the candidate survives the
failing baseline cases and keyboard switching on the affected device. Keep device
results separate from unit-test results.
