package io.github.certhfds2j7.devinlocal

import org.junit.Assert.*
import org.junit.Test

class TermuxResultPolicyTest {
    @Test fun acceptsOfficialTermuxSuccessAndRejectsCancellationAndExecutionErrors() {
        assertTrue(TermuxResultPolicy.success(0, -1))
        assertFalse(TermuxResultPolicy.success(0, 0)) // Activity.RESULT_CANCELED
        assertFalse(TermuxResultPolicy.success(0, 2)) // Termux internal failure
        assertFalse(TermuxResultPolicy.success(1, -1)) // Shell failed
        assertFalse(TermuxResultPolicy.success(null, -1))
        assertFalse(TermuxResultPolicy.success(0, null))
    }
}
