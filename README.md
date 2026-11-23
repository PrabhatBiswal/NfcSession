# NfcSession

Phase 1 of a native Android NFC reader SDK: a session and reliability layer for NFC-A (NFC Forum Type 2) tags, written in Kotlin with no third-party dependencies.

It is built for sensor patches that are **activated** with a multi-step write and later **read out** in bulk, where a user holding a small tag against a phone is the unreliable part.

## Status

| | |
|---|---|
| Reads and writes generic Type 2 tags (NTAG21x and similar) | Built |
| Unit tests, including a 300-case random-fault soak | 8 passing |
| Full write and read-back through the simulated tag on a phone | Verified |
| Run against a physical tag on an NFC phone | **Not yet done** |
| Commands for a specific vendor patch | **Not included** (phase 2) |

The radio path (reader mode, tag re-activation, NFC-off handling) compiles but has not run against real hardware. Expect to tune the timing settings per phone once it does.

## What it handles

- **Reader mode only.** NFC-A, system NDEF check skipped, no activity pause on tap, all I/O off the main thread.
- **Tag already on the phone.** A tag that was in place before the operation started is used without a lift and re-tap.
- **Typed errors.** Tag lost, stale handle, RF glitch, wrong tag, refusal by the tag, NFC off, no NFC, cancelled. Each is marked "ask for a re-tap" or "give up".
- **Retry that can succeed.** A Type 2 tag goes silent after any error, so each retry re-activates it first. Three retries per command with 20/40/60 ms backoff.
- **Glitch versus refusal.** A corrupted reply is retried; a NAK for a bad, locked or protected page fails at once.
- **Resume across taps.** A read or write continues from where the last tap stopped, and refuses a different tag.
- **Verified, journaled writes.** Every page is read back. A marker page written first ("pending") and last ("done") makes a half-finished write detectable.
- **Wrong card in the field.** A bank card in the phone case or a MIFARE Classic card is rejected before any I/O and does not use up a tap.
- **Fewer commands.** FAST_READ with a frame size that halves on failure (a whole NTAG215 in 5 commands instead of 33), and one verify read per four written pages.
- **Diagnostics.** Each run reports device model, taps, time in field, total time and a timed log.

## Layout

- `nfcsession/` is the library.
  - `Type2.kt` holds the tag logic with no Android dependency: errors, `Transport`, `Type2Tag`, `ReadJob`, `WriteJob`, `SimulatedTag`.
  - `NfcSession.kt` is the Android side: reader mode, exception mapping, events, the report.
- `app/` is a demo with "Read whole tag", "Journaled write" and a running success tally.

## Usage

```kotlin
val session = NfcSession(activity) // call session.stop() from onPause

session.start(ReadJob()) { event ->
    when (event) {
        is Event.Waiting -> showHoldPrompt(event.why)      // why != null: tap again
        is Event.Progress -> showProgress(event.fraction)
        is Event.Done -> use(event.report)
        is Event.Failed -> showError(event.error, event.report)
    }
}
```

Timeouts, retries, backoff, presence-check delay and the maximum number of taps are fields of `NfcSession.Config`.

## Testing without a tag or an NFC phone

`SimulatedTag` is an in-memory tag that loses commands, tears writes and "moves away" after a set number of commands. It models the tag, not the radio.

- **Unit tests:** `./gradlew :nfcsession:testDebugUnitTest`
- **On a phone:** the demo switches to the simulated tag when the phone has no NFC. Start an operation, then press "Simulate a tap" until it completes.
- **Logs:** `adb logcat -s NfcSession`

## Build

Needs JDK 17 and the Android SDK (compile SDK 35, minimum Android 8.0).

```bash
./gradlew :nfcsession:testDebugUnitTest :app:assembleDebug
```

## Known limits

- Needs an NDEF-formatted tag of at most 255 pages; larger tags need sector selection.
- The demo's journaled write overwrites user pages 4 to 19. It never touches lock or configuration pages.
- A phone without NFC cannot be given it in software. `Transport` is the place to attach an external reader.

## Phase 2: what is needed

To drive a specific patch, the library needs a command layer on top of `Transport`. That requires, from whoever owns the patch firmware:

- the chip and its memory map;
- the activation and read sequences;
- the record format and calibration formula;
- any password or lock protection;
- two or three sample memory dumps with their expected decoded values;
- one or two physical patches, or failure logs from affected phones.
