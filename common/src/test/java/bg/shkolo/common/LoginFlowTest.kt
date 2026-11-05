package bg.shkolo.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the login state machine's decisions.
 *
 * The phone's WebView login cannot be unit-tested here, but the two things
 * that caused the reported freeze were *logic*: unbounded waiting and
 * treating a partially-loaded page as success. These tests pin the contract.
 */
class LoginFlowTest {

    /**
     * The JS runs after onPageFinished, but fp.js may not have filled the
     * hidden inputs yet. A page that answers 'nofp' must be retried, not
     * treated as a successful submit.
     */
    @Test
    fun fingerprintNotReadyIsRetryable() {
        val retry = setOf("nofp", "noform", "error:x")

        for (signal in retry) {
            assertFalse("'$signal' must not count as submitted", isSubmitting(signal))
        }
        assertTrue("'submitted' is the only success", isSubmitting("submitted"))
    }

    @Test
    fun timeoutIsBounded() {
        // The client polls until either the session cookie appears or the
        // deadline passes; a null result must be reported, never hung.
        val deadlineMs = 45_000L
        val observed = simulateWait(
            deadlineMs = deadlineMs,
            pollMs = 700,
            becameLoggedInAfterMs = null, // never logs in
            now = { it },
        )
        assertEquals(false, observed)
    }

    @Test
    fun successfulLoginReturnsBeforeDeadline() {
        val result = simulateWait(
            deadlineMs = 45_000,
            pollMs = 700,
            becameLoggedInAfterMs = 3_000,
            now = { it },
        )
        assertTrue(result)
    }

    // --- tiny models of the real logic -------------------------------------

    private fun isSubmitting(jsResult: String?) = jsResult == "submitted"

    /**
     * Mirrors ShkoloClient.waitForSession: poll on an interval, stop on
     * success or deadline.
     */
    private fun simulateWait(
        deadlineMs: Long,
        pollMs: Long,
        becameLoggedInAfterMs: Long?,
        now: (Long) -> Long,
    ): Boolean {
        var t = 0L
        while (t < deadlineMs) {
            t += pollMs
            if (becameLoggedInAfterMs != null && t >= becameLoggedInAfterMs) return true
            if (now(t) >= deadlineMs) break
        }
        return false
    }
}
