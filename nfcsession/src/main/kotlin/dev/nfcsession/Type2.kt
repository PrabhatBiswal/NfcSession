package dev.nfcsession

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Every failure a caller can see. [retryOnRetap]: keep the job and ask the user to tap again. */
sealed class NfcError(message: String, val retryOnRetap: Boolean) : Exception(message) {
    class TagLost : NfcError("Tag moved away", true)
    class Stale : NfcError("Tag handle out of date", true) // Android 13+ SecurityException
    class Io(message: String) : NfcError(message, true)    // RF glitch; retried in place first
    class WrongTag(message: String) : NfcError(message, true)
    class Protocol(message: String) : NfcError(message, false) // the tag itself said no; retrying cannot help
    class Cancelled : NfcError("Cancelled", false)
    class NfcOff : NfcError("NFC is turned off", false)
    class NoNfc : NfcError("This phone has no NFC", false)
}

/** The seam a vendor command layer, an external reader or a simulated tag plugs into. Throws [NfcError] only. */
interface Transport {
    val uid: ByteArray

    /** Largest response the reader can deliver in one frame. */
    val maxFrame: Int get() = 253
    fun transceive(command: ByteArray): ByteArray

    /** Re-activate the tag. A Type 2 tag goes silent after any error, so a retry without this always fails. */
    fun reconnect() {}
}

/** NFC Forum Type 2 commands with in-place retry of RF glitches and read-back verified writes. */
class Type2Tag(
    private val io: Transport,
    private val retries: Int = 3,
    private val backoffMs: Long = 20,
    private val log: (String) -> Unit = {},
) {
    val uid: ByteArray get() = io.uid
    private var chunk = -1 // pages per FAST_READ; -1 not probed yet, 0 unsupported

    /** User memory, taken from the capability container on page 3. */
    // ponytail: needs an NDEF-formatted tag (NTAG21x ship that way) of at most 255 pages;
    // bigger tags need SECTOR_SELECT, blank ones need sizing from GET_VERSION.
    fun userPages(): IntRange {
        val cc = read4(3)
        if (cc[0] != 0xE1.toByte()) throw NfcError.WrongTag("Not an NDEF-formatted Type 2 tag")
        val pages = (cc[2].toInt() and 0xFF) * 2 // size byte is in units of 8 bytes = 2 pages
        if (pages == 0) throw NfcError.WrongTag("Tag reports no user memory")
        return 4..(3 + pages)
    }

    /** Returns 16 bytes: [page] and the three pages after it. */
    fun read4(page: Int): ByteArray = retrying("READ $page") { rawRead(page) }

    /**
     * Reads up to [count] pages from [page] and returns however many it got (at least one page).
     * Uses FAST_READ where the tag has it: one big frame instead of many small ones means less
     * time holding still. A big frame is also easier to corrupt, so the size halves on every failure.
     */
    // ponytail: the size only ever shrinks within a tap; grow it back (AIMD) if long reads on weak coupling matter.
    fun read(page: Int, count: Int): ByteArray {
        if (chunk < 0) chunk = if (hasFastRead()) minOf(60, io.maxFrame / 4) else 0
        while (chunk >= 8) {
            val n = minOf(chunk, count)
            try {
                val r = io.transceive(byteArrayOf(0x3A, page.toByte(), (page + n - 1).toByte()))
                if (r.size == n * 4) return r
                bad("FAST_READ $page+$n", r)
            } catch (e: NfcError.Io) {
                chunk /= 2
                log("FAST_READ of $n pages failed (${e.message}), now $chunk at a time")
                io.reconnect()
            }
        }
        return read4(page).copyOf(minOf(4, count) * 4)
    }

    /**
     * Writes 1 to 4 consecutive pages ([data] is 4, 8, 12 or 16 bytes) and verifies them with a
     * single read-back. Rewriting the same bytes is harmless, so the whole group is retried.
     */
    fun write(page: Int, data: ByteArray) {
        require(data.size in 4..16 && data.size % 4 == 0) { "Write 1 to 4 whole pages" }
        retrying("WRITE $page") {
            for (k in 0 until data.size / 4) {
                val ack = io.transceive(byteArrayOf(0xA2.toByte(), (page + k).toByte()) + data.copyOfRange(k * 4, k * 4 + 4))
                if (ack.size != 1 || (ack[0].toInt() and 0x0F) != 0x0A) bad("WRITE ${page + k}", ack)
            }
            if (!rawRead(page).copyOf(data.size).contentEquals(data)) throw NfcError.Io("WRITE $page: read-back differs")
        }
    }

    private fun hasFastRead(): Boolean {
        val ok = try { io.transceive(byteArrayOf(0x60)).size == 8 } catch (e: NfcError.Io) { false } // GET_VERSION
        if (!ok) io.reconnect() // a tag without the command rejected it and went silent
        return ok
    }

    private fun rawRead(page: Int): ByteArray {
        val r = io.transceive(byteArrayOf(0x30, page.toByte()))
        if (r.size != 16) bad("READ $page", r)
        return r
    }

    /** Sorts an unexpected answer into "glitch, try again" or "the tag refused, stop". */
    private fun bad(what: String, r: ByteArray): Nothing {
        if (r.size == 1) when (val nak = r[0].toInt() and 0x0F) {
            0x01, 0x05 -> throw NfcError.Io("$what: NAK $nak (CRC or EEPROM write error)")
            else -> throw NfcError.Protocol("$what refused (NAK $nak): page out of range, locked or protected")
        }
        throw NfcError.Io("$what: got ${r.size} bytes")
    }

    private fun <T> retrying(what: String, block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: NfcError.Io) {
                if (attempt == retries) throw e
                attempt++
                log("$what failed (${e.message}), retry $attempt/$retries")
                Thread.sleep(backoffMs * attempt)
                io.reconnect()
            }
        }
    }
}

/**
 * A resumable operation. [runOn] continues where the previous tap stopped and throws [NfcError]
 * when interrupted; progress survives, so calling it again with the same tag carries on.
 */
abstract class TagJob {
    private var tagUid: ByteArray? = null
    abstract val done: Boolean
    abstract val progress: Float
    protected abstract fun step(tag: Type2Tag)

    fun runOn(tag: Type2Tag, onProgress: (Float) -> Unit = {}) {
        val uid = tagUid
        if (uid == null) tagUid = tag.uid
        else if (!uid.contentEquals(tag.uid)) throw NfcError.WrongTag("Different tag than the one this started on")
        while (!done) {
            step(tag)
            onProgress(progress)
        }
    }
}

/** Reads [range], or the whole user memory when null. */
class ReadJob(private var range: IntRange? = null) : TagJob() {
    private val buf = ByteArrayOutputStream()
    private var next = 0
    val data: ByteArray get() = buf.toByteArray()
    override val done get() = range?.let { next > it.last } ?: false
    override val progress get() = range?.let { (next - it.first).toFloat() / (it.last - it.first + 1) } ?: 0f

    override fun step(tag: Type2Tag) {
        val r = range ?: tag.userPages().also { range = it }
        if (next < r.first) next = r.first
        val got = tag.read(next, r.last - next + 1)
        buf.write(got)
        next += got.size / 4
    }
}

/** Writes pages in order, each verified. Put a marker page first and last to make it a journal. */
class WriteJob(private val steps: List<Pair<Int, ByteArray>>) : TagJob() {
    private var i = 0
    override val done get() = i == steps.size
    override val progress get() = i.toFloat() / steps.size

    override fun step(tag: Type2Tag) {
        var n = 1 // consecutive pages go out as one group with one verify read
        while (n < 4 && i + n < steps.size && steps[i + n].first == steps[i].first + n) n++
        tag.write(steps[i].first, steps.subList(i, i + n).fold(ByteArray(0)) { all, s -> all + s.second })
        i += n
    }
}

/**
 * An in-memory NTAG-like tag that misbehaves on demand. Lets the whole stack run in unit tests and
 * on phones without NFC. It models the tag, not the radio: antenna coupling still needs real hardware.
 */
class SimulatedTag(
    private val pages: Int = 135, // NTAG215
    override val uid: ByteArray = byteArrayOf(4, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
    private val random: Random = Random(1),
    var glitchRate: Double = 0.0,            // chance a command is lost to RF noise
    var dropWriteRate: Double = 0.0,         // chance a write is ACKed but not stored (torn write)
    var commandsPerTap: Int = Int.MAX_VALUE, // the user pulls the tag away after this many commands
    private val fastRead: Boolean = true,
) : Transport {
    val mem = ByteArray(pages * 4).also {
        it[12] = 0xE1.toByte(); it[13] = 0x10; it[14] = ((pages - 9) / 2).toByte() // capability container
    }
    var commands = 0
        private set
    private var left = commandsPerTap
    private var silent = false

    /** A fresh tap: the tag is back in the field. */
    fun tap() = apply { left = commandsPerTap; silent = false }

    override fun reconnect() {
        if (left <= 0) throw NfcError.TagLost()
        silent = false
    }

    override fun transceive(command: ByteArray): ByteArray {
        commands++
        if (left-- <= 0) throw NfcError.TagLost()
        if (silent) throw NfcError.Io("tag is idle after an error")
        if (random.nextDouble() < glitchRate) { silent = true; throw NfcError.Io("RF glitch") }
        val p = if (command.size > 1) command[1].toInt() and 0xFF else 0
        return when (command[0].toInt() and 0xFF) {
            0x30 -> if (p < pages) ByteArray(16) { mem[(p * 4 + it) % mem.size] } else nak()
            0x3A -> {
                val end = command[2].toInt() and 0xFF
                if (fastRead && p <= end && end < pages) mem.copyOfRange(p * 4, (end + 1) * 4) else nak()
            }
            0x60 -> if (fastRead) byteArrayOf(0, 4, 4, 2, 1, 0, 0x11, 3) else nak()
            0xA2 -> if (p in 2 until pages) {
                if (random.nextDouble() >= dropWriteRate) command.copyInto(mem, p * 4, 2, 6)
                byteArrayOf(0x0A)
            } else nak()
            else -> nak()
        }
    }

    private fun nak(): ByteArray { silent = true; return byteArrayOf(0x00) }
}
