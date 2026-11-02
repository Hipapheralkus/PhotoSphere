package app.photosphere

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class StitchLifecycleTest {
    @Test fun cancellationBeforeServiceStartsPreventsPublication() {
        val work = work()
        assertTrue(work.cancel())
        var wrote = false
        assertFalse(work.commit { wrote = true })
        assertFalse(wrote)
        assertFalse(work.committed.get())
    }

    @Test fun cancellationWaitsForCommitAndDoesNotClaimToCancelPublishedOutput() {
        val work = work()
        val writing = CountDownLatch(1)
        val finishWriting = CountDownLatch(1)
        val cancellationAttempted = CountDownLatch(1)
        val cancellationResult = AtomicReference<Boolean>()
        val failure = AtomicReference<Throwable>()
        val writer = Thread {
            try {
                assertTrue(work.commit {
                    writing.countDown()
                    check(finishWriting.await(5, TimeUnit.SECONDS))
                })
            } catch (error: Throwable) { failure.set(error) }
        }
        val canceller = Thread {
            cancellationAttempted.countDown()
            cancellationResult.set(work.cancel())
        }
        try {
            writer.start()
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            canceller.start()
            assertTrue(cancellationAttempted.await(5, TimeUnit.SECONDS))
        } finally {
            finishWriting.countDown()
            writer.join(5000)
            if (canceller.state != Thread.State.NEW) canceller.join(5000)
        }
        failure.get()?.let { throw it }
        assertFalse(writer.isAlive)
        assertFalse(canceller.isAlive)
        assertEquals(false, cancellationResult.get())
        assertTrue(work.committed.get())
        assertFalse(work.cancelled.get())
    }

    @Test fun failedCommitDoesNotMarkOutputPublishedAndStillAcceptsCancellation() {
        val work = work()
        try {
            work.commit { throw IOException("Disk full") }
            throw AssertionError("Expected the failed write to propagate")
        } catch (_: IOException) { }
        assertFalse(work.committed.get())
        assertTrue(work.cancel())
        assertFalse(work.commit { throw AssertionError("Cancelled output was published") })
    }

    @Test fun restartInterruptsEveryUnpublishedActiveStateAndPreservesEarlierResult() {
        val prior = result("prior")
        for (status in listOf(StitchStatus.QUEUED, StitchStatus.RUNNING, StitchStatus.CANCELLING)) {
            val recovered = StitchRecovery.reconcile(state("new", status), prior, 2000)!!
            assertEquals(StitchStatus.INTERRUPTED, recovered.status)
            assertFalse(recovered.isActive)
            assertSame(prior, recovered.result)
        }
    }

    @Test fun restartRecognizesCommitThatFinishedBeforeItsProgressJournal() {
        val published = result("new")
        val recovered = StitchRecovery.reconcile(state("new", StitchStatus.RUNNING), published, 2000)!!
        assertEquals(StitchStatus.SUCCEEDED, recovered.status)
        assertEquals(100, recovered.progress)
        assertSame(published, recovered.result)
    }

    @Test fun successfulJournalCannotClaimAnAbsentPanoramaIsReady() {
        val recovered = StitchRecovery.reconcile(state("new", StitchStatus.SUCCEEDED), null, 2000)!!
        assertEquals(StitchStatus.FAILED, recovered.status)
        assertTrue(recovered.message.contains("missing"))
    }

    @Test fun failedReplacementKeepsFailureAndPreviousPublishedPanorama() {
        val prior = result("prior")
        val recovered = StitchRecovery.reconcile(state("new", StitchStatus.FAILED), prior, 2000)!!
        assertEquals(StitchStatus.FAILED, recovered.status)
        assertSame(prior, recovered.result)
    }

    private fun work() = StitchWork("session", "new", StitchQuality.FULL)
    private fun state(job: String, status: StitchStatus) =
        StitchJobState("session", job, StitchQuality.FULL, status, 40, "Working", updatedAtMs = 1000)
    private fun result(job: String) = PanoramaResult("session", job, File("panorama.jpg"), File("preview.jpg"), File("panorama.json"),
        512, 256, 2, .25, 1500, emptyList(), 2, 1, StitchQuality.FULL, 1000, .2)
}
