package dev.reviewsmith.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GateOutcomeTest {

    @Test
    fun `exit 0 is OK regardless of failOnGate`() {
        assertEquals(ReviewsmithTask.Outcome.OK, ReviewsmithTask.gateOutcome(0, failOnGate = true))
        assertEquals(ReviewsmithTask.Outcome.OK, ReviewsmithTask.gateOutcome(0, failOnGate = false))
    }

    @Test
    fun `exit 3 fails the build when failOnGate is true`() {
        assertEquals(ReviewsmithTask.Outcome.GATE_FAIL, ReviewsmithTask.gateOutcome(3, failOnGate = true))
    }

    @Test
    fun `exit 3 is advisory when failOnGate is false`() {
        assertEquals(ReviewsmithTask.Outcome.GATE_ADVISORY, ReviewsmithTask.gateOutcome(3, failOnGate = false))
    }

    @Test
    fun `other non-zero exit codes are errors`() {
        assertEquals(ReviewsmithTask.Outcome.ERROR, ReviewsmithTask.gateOutcome(1, failOnGate = true))
        assertEquals(ReviewsmithTask.Outcome.ERROR, ReviewsmithTask.gateOutcome(2, failOnGate = false))
    }
}
