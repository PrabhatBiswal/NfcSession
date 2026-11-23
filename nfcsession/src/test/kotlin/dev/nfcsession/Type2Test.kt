package dev.nfcsession

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A 40-page plain Type 2 tag (no FAST_READ) that fails on exactly the command numbers a test picks. */
private class FakeTag(override val uid: ByteArray = byteArrayOf(4, 1, 2, 3)) : Transport {
    val mem = ByteArray(40 * 4) { it.toByte() }.also {
        it[12] = 0xE1.toByte(); it[13] = 0x10; it[14] = 16; it[15] = 0 // CC: 16*8 bytes = pages 4..35
    }
    val failAt = mutableMapOf<Int, NfcError>()
    val dropWriteAt = mutableSetOf<Int>() // ACK the write but do not store it
    var calls = 0

    override fun transceive(command: ByteArray): ByteArray {
        calls++
        failAt.remove(calls)?.let { throw it }
        val page = if (command.size > 1) command[1].toInt() and 0xFF else 0
        return when (command[0]) {
            0x30.toByte() -> ByteArray(16) { mem[(page * 4 + it) % mem.size] }
            0xA2.toByte() -> {
                if (!dropWriteAt.remove(calls)) command.copyInto(mem, page * 4, 2, 6)
                byteArrayOf(0x0A)
            }
            else -> byteArrayOf(0x00)
        }
    }
}

private fun tag(io: Transport) = Type2Tag(io, retries = 3, backoffMs = 0)
private val journal = listOf(4 to "PEND".toByteArray(), 5 to "DATA".toByteArray(), 4 to "DONE".toByteArray())

class Type2Test {
    @Test fun readResumesAfterTagLostWithoutStartingOver() {
        val f = FakeTag().apply { failAt[5] = NfcError.TagLost() }
        val job = ReadJob()
        try { job.runOn(tag(f)); fail("should have been interrupted") } catch (e: NfcError.TagLost) {}
        assertTrue(job.progress > 0f && !job.done)
        job.runOn(tag(f))
        assertArrayEquals(f.mem.copyOfRange(16, 144), job.data)
        assertEquals(1 + 2 + 8 + 1, f.calls) // CC, a FAST_READ probe per tap, 8 reads, the lost one; nothing repeated
    }

    @Test fun glitchesAndDroppedWritesAreRetriedInPlace() {
        val f = FakeTag().apply { failAt[1] = NfcError.Io("glitch"); dropWriteAt += 3 }
        WriteJob(journal).runOn(tag(f))
        assertEquals("DONEDATA", String(f.mem, 16, 8))
    }

    @Test fun interruptedJournalIsVisibleThenCompletes() {
        val f = FakeTag().apply { failAt[3] = NfcError.TagLost() }
        val job = WriteJob(journal)
        try { job.runOn(tag(f)); fail() } catch (e: NfcError.TagLost) {}
        assertEquals("PEND", String(f.mem, 16, 4)) // half-written state is detectable on the tag
        job.runOn(tag(f))
        assertEquals("DONEDATA", String(f.mem, 16, 8))
    }

    @Test fun resumingOnADifferentTagIsRefused() {
        val job = ReadJob()
        val first = FakeTag().apply { failAt[2] = NfcError.TagLost() }
        try { job.runOn(tag(first)); fail() } catch (e: NfcError.TagLost) {}
        try { job.runOn(tag(FakeTag(byteArrayOf(4, 9, 9, 9)))); fail() } catch (e: NfcError.WrongTag) {}
    }

    @Test fun persistentGlitchSurfacesAfterRetries() {
        val f = FakeTag().apply { (1..4).forEach { failAt[it] = NfcError.Io("glitch") } }
        try { ReadJob().runOn(tag(f)); fail() } catch (e: NfcError.Io) {}
        assertEquals(4, f.calls) // 1 try + 3 retries
    }

    @Test fun fastReadTakesAWholeTagInAFewCommands() {
        val sim = SimulatedTag()
        val job = ReadJob().also { it.runOn(tag(sim)) }
        assertArrayEquals(sim.mem.copyOfRange(16, 16 + 126 * 4), job.data)
        assertEquals(5, sim.commands) // CC, probe, 3 FAST_READs; plain READ would need 33
    }

    @Test fun aRefusalFromTheTagFailsFastInsteadOfRetrying() {
        val sim = SimulatedTag()
        try { WriteJob(listOf(200 to "NOPE".toByteArray())).runOn(tag(sim)); fail() } catch (e: NfcError.Protocol) {}
        assertEquals(1, sim.commands)
    }

    /** 300 differently-unlucky tags: every job must finish, and what ends up on the tag must be exact. */
    @Test fun soakUnderRandomFaults() {
        for (seed in 1..300) {
            val sim = SimulatedTag(
                random = Random(seed), glitchRate = 0.15, dropWriteRate = 0.1, commandsPerTap = 9,
                fastRead = seed % 2 == 0,
            )
            val steps = listOf(4 to "PEND".toByteArray()) +
                (5..40).map { it to byteArrayOf(it.toByte(), seed.toByte(), 7, 9) } + listOf(4 to "DONE".toByteArray())
            finish(WriteJob(steps), sim, seed)
            val read = ReadJob().also { finish(it, sim, seed) }

            assertEquals("seed $seed", "DONE", String(read.data, 0, 4))
            for (p in 5..40) assertArrayEquals("seed $seed page $p", byteArrayOf(p.toByte(), seed.toByte(), 7, 9), read.data.copyOfRange((p - 4) * 4, (p - 3) * 4))
            assertArrayEquals("seed $seed", sim.mem.copyOfRange(16, 16 + 126 * 4), read.data)
        }
    }

    private fun finish(job: TagJob, sim: SimulatedTag, seed: Int) {
        repeat(400) {
            try { job.runOn(tag(sim.tap())); return } catch (e: NfcError) { assertTrue("seed $seed: ${e.message}", e.retryOnRetap) }
        }
        fail("seed $seed never finished")
    }
}
