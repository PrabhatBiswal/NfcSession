package dev.nfcsession

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import kotlin.concurrent.thread

sealed class Event {
    /** Hold the tag to the phone; [why] is set when a previous tap was interrupted. */
    data class Waiting(val why: NfcError?) : Event()
    data class Progress(val fraction: Float) : Event()
    data class Done(val report: Report) : Event()
    data class Failed(val error: NfcError, val report: Report) : Event()
}

data class Report(val device: String, val taps: Int, val inFieldMs: Long, val totalMs: Long, val log: List<String>)

/**
 * Runs a [TagJob] against whatever NFC-A tag the user taps, across as many taps as it takes.
 * All tag I/O happens on a background thread; events arrive on the main thread.
 * Call [start] while the activity is resumed and [stop] from onPause. A job stopped part-way
 * keeps its progress: pass the same job to [start] again to carry on.
 */
class NfcSession(private val activity: Activity, private val cfg: Config = Config()) {

    /** Tuning knobs: phones and tags differ, so none of these is hard-coded. */
    data class Config(
        val presenceCheckDelayMs: Int = 1000, // how rarely Android pings the tag behind our back
        val timeoutMs: Int = 300,             // per command
        val retries: Int = 3,                 // per command, before asking for a re-tap
        val backoffMs: Long = 20,
        val maxTaps: Int = 6,                 // then give up instead of asking forever
        val builtInNfc: Boolean = true,       // false: taps only come from tap(), e.g. a simulated tag or external reader
    )

    private val main = Handler(Looper.getMainLooper())
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    @Volatile private var run: Run? = null
    @Volatile private var lastTag: Tag? = null
    private var readerOn = false

    private val nfcSwitch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, 0) != NfcAdapter.STATE_OFF) return
            val r = run
            stop() // reader mode does not survive the radio restarting; the next start() sets it up again
            r?.finish(NfcError.NfcOff()) // otherwise the user waits forever on a dead radio
        }
    }

    fun start(job: TagJob, onEvent: (Event) -> Unit) {
        val r = Run(job, onEvent)
        if (cfg.builtInNfc) {
            val a = adapter ?: return r.finish(NfcError.NoNfc())
            if (!a.isEnabled) return r.finish(NfcError.NfcOff())
            if (!readerOn) {
                // Reader mode: no activity pause on tap, no system NDEF read first, NFC-A only.
                // It stays on after a job ends so a tag still on the phone is not handed to another app.
                a.enableReaderMode(
                    activity,
                    { tag -> lastTag = tag; run?.onTag(tag, fresh = true) },
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, cfg.presenceCheckDelayMs) },
                )
                val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(nfcSwitch, filter, Context.RECEIVER_EXPORTED)
                else activity.registerReceiver(nfcSwitch, filter)
                readerOn = true
            }
        }
        run = r
        r.emit(Event.Waiting(null))
        // The user may already be holding the tag: try the one we last saw instead of demanding a re-tap.
        lastTag?.let { thread(name = "nfc-session") { r.onTag(it, fresh = false) } }
    }

    /** A tap that does not come from the phone's own NFC chip: a [SimulatedTag] or an external reader. */
    fun tap(io: Transport) {
        thread(name = "nfc-session") { run?.attempt(fresh = true, open = { io }) }
    }

    fun stop() {
        run = null
        lastTag = null
        if (readerOn) {
            adapter?.disableReaderMode(activity)
            activity.unregisterReceiver(nfcSwitch)
            readerOn = false
        }
    }

    private inner class Run(val job: TagJob, val onEvent: (Event) -> Unit) {
        private val t0 = SystemClock.elapsedRealtime()
        private var taps = 0
        private var inFieldMs = 0L
        private val log = mutableListOf<String>()

        fun emit(e: Event) { main.post { onEvent(e) } }

        private fun note(s: String) {
            val line = "+${SystemClock.elapsedRealtime() - t0}ms $s"
            synchronized(log) { log += line }
            Log.i(TAG, line)
        }

        fun onTag(tag: Tag, fresh: Boolean) {
            val nfcA = NfcA.get(tag)
            // SAK 0 means Type 2. Anything else (a bank card in the phone case, MIFARE Classic)
            // is turned away before any I/O, so it never uses up one of the user's taps.
            if (nfcA == null || nfcA.sak.toInt() != 0) {
                if (fresh && run === this) emit(Event.Waiting(NfcError.WrongTag("That is not the right kind of tag")))
                return
            }
            attempt(
                fresh,
                open = { AndroidTransport(nfcA, tag.id, cfg.timeoutMs).also { it.open() } },
                close = { runCatching { nfcA.close() } },
            )
        }

        @Synchronized
        fun attempt(fresh: Boolean, open: () -> Transport, close: () -> Unit = {}) {
            if (run !== this || job.done) return
            val start = SystemClock.elapsedRealtime()
            try {
                val io = open()
                taps++
                note("tag ${io.uid.joinToString("") { "%02X".format(it) }} connected, tap $taps")
                job.runOn(Type2Tag(io, cfg.retries, cfg.backoffMs, ::note)) {
                    if (run !== this) throw NfcError.Cancelled() // stop() was called mid-flight
                    emit(Event.Progress(it))
                }
                finish(null)
            } catch (e: NfcError) {
                note("interrupted: ${e.message}")
                when {
                    e is NfcError.Cancelled -> Unit
                    !fresh && e.retryOnRetap -> Unit // the remembered tag was already gone; keep waiting
                    !e.retryOnRetap || taps >= cfg.maxTaps -> finish(e)
                    else -> emit(Event.Waiting(e))
                }
            } finally {
                inFieldMs += SystemClock.elapsedRealtime() - start
                close()
            }
        }

        fun finish(error: NfcError?) {
            if (run === this) run = null
            val report = Report(
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
                taps, inFieldMs, SystemClock.elapsedRealtime() - t0, synchronized(log) { log.toList() },
            )
            emit(if (error == null) Event.Done(report) else Event.Failed(error, report))
        }
    }

    private class AndroidTransport(
        private val nfcA: NfcA,
        override val uid: ByteArray,
        private val timeoutMs: Int,
    ) : Transport {
        override val maxFrame: Int get() = nfcA.maxTransceiveLength
        fun open() = mapped { nfcA.connect(); nfcA.timeout = timeoutMs }
        override fun transceive(command: ByteArray): ByteArray = mapped { nfcA.transceive(command) }

        // Android's close() re-activates the tag and resets timeouts, which is exactly the recovery a retry needs.
        override fun reconnect() = mapped { nfcA.close(); nfcA.connect(); nfcA.timeout = timeoutMs }
    }

    private companion object {
        const val TAG = "NfcSession"

        /** Android reports every NFC failure as some exception; give each a distinct, typed meaning. */
        inline fun <T> mapped(block: () -> T): T = try {
            block()
        } catch (e: TagLostException) {
            throw NfcError.TagLost()
        } catch (e: SecurityException) {
            throw NfcError.Stale()
        } catch (e: IOException) {
            throw NfcError.Io(e.message ?: "I/O error")
        } catch (e: IllegalStateException) {
            throw NfcError.TagLost() // closed or never connected
        }
    }
}
