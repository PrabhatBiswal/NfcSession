package dev.nfcsession.demo

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import dev.nfcsession.Event
import dev.nfcsession.NfcSession
import dev.nfcsession.ReadJob
import dev.nfcsession.Report
import dev.nfcsession.SimulatedTag
import dev.nfcsession.TagJob
import dev.nfcsession.WriteJob
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var session: NfcSession
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var stats: TextView
    private lateinit var logView: TextView

    // A deliberately bad tag: 1 command in 10 is lost, 1 write in 20 is torn, and the "user"
    // pulls it away after 12 commands. Everything except the radio runs exactly as with a real tag.
    private val simTag = SimulatedTag(random = Random(System.nanoTime()), glitchRate = 0.1, dropWriteRate = 0.05, commandsPerTap = 12)

    private var runs = 0
    private var ok = 0
    private var taps = 0
    private var inFieldMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NFC polling stops when the screen turns off, so do not let it time out mid-scan.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val hasNfc = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
        session = NfcSession(this, NfcSession.Config(builtInNfc = hasNfc, maxTaps = if (hasNfc) 6 else 12))

        status = TextView(this).apply { textSize = 20f; text = "Pick an operation" }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        stats = TextView(this)
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        val simulate = CheckBox(this).apply {
            text = if (hasNfc) "Use simulated flaky tag" else "Simulated flaky tag (this phone has no NFC)"
            isChecked = !hasNfc
            isEnabled = hasNfc
            setOnCheckedChangeListener { _, on ->
                session.stop()
                session = NfcSession(this@MainActivity, NfcSession.Config(builtInNfc = !on, maxTaps = if (on) 12 else 6))
                status.text = "Pick an operation"
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(40, 40, 40, 40)
            addView(status)
            addView(bar)
            addView(simulate)
            addView(button("Read whole tag") { run(ReadJob()) })
            addView(button("Journaled write (17 steps)") { run(journaledWrite()) })
            addView(button("Simulate a tap") { if (simulate.isChecked) session.tap(simTag.tap()) })
            addView(button("Cancel") { session.stop(); status.text = "Cancelled" })
            addView(stats)
            addView(ScrollView(context).apply { addView(logView) })
        })
    }

    override fun onPause() {
        super.onPause()
        session.stop() // reader mode only exists while the activity is in front
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { onClick() } }

    /** Stand-in for a multi-step activation: marker PEND, the payload, then marker DONE. */
    // Overwrites user pages 4..19 of the tag. Never touches lock or config pages.
    // Rule for readers: anything other than DONE in the marker page means "not finished".
    private fun journaledWrite(): WriteJob {
        val stamp = (System.currentTimeMillis() / 1000).toInt()
        val payload = (5..19).map { page ->
            page to byteArrayOf(page.toByte(), (stamp shr 16).toByte(), (stamp shr 8).toByte(), stamp.toByte())
        }
        return WriteJob(listOf(4 to "PEND".toByteArray()) + payload + listOf(4 to "DONE".toByteArray()))
    }

    private fun run(job: TagJob) {
        bar.progress = 0
        session.start(job) { e ->
            when (e) {
                is Event.Waiting -> status.text =
                    e.why?.let { "${it.message}. Hold the tag to the phone again." } ?: "Hold the tag to the back of the phone"
                is Event.Progress -> { bar.progress = (e.fraction * 100).toInt(); status.text = "Keep holding still…" }
                is Event.Done -> { record(true, e.report); status.text = describe(job) }
                is Event.Failed -> { record(false, e.report); status.text = "Failed: ${e.error.message}" }
            }
        }
    }

    private fun describe(job: TagJob) = if (job is ReadJob) {
        val marker = String(job.data, 0, minOf(4, job.data.size), Charsets.ISO_8859_1)
        "Read ${job.data.size} bytes. " + when (marker) {
            "DONE" -> "Last journaled write completed."
            "PEND" -> "This tag was left half-written."
            else -> "No journal marker."
        }
    } else "Write complete, every page verified"

    private fun record(success: Boolean, r: Report) {
        runs++; if (success) ok++
        taps += r.taps; inFieldMs += r.inFieldMs
        stats.text = "${r.device}\n$ok/$runs succeeded · avg ${"%.1f".format(taps.toFloat() / runs)} taps · " +
            "avg ${inFieldMs / runs} ms in field · last ${r.totalMs} ms total"
        logView.text = r.log.joinToString("\n")
    }
}
