package me.rerere.rikkahub.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutonomousTaskTest {
    @Test
    fun planStartsFirstStepAndAdvancesToSuccess() {
        val task = AutonomousTask(goal = "inspect repository")
            .withPlan(listOf(AutonomousStep(title = "Inspect")))
        val completed = task
            .updateStep(task.currentStep!!.succeeded("done"))
            .advance()

        assertEquals(AutonomousTaskStatus.succeeded, completed.status)
        assertEquals(1, completed.currentStepIndex)
    }

    @Test
    fun retryStopsAtConfiguredIterationLimit() {
        val task = AutonomousTask(goal = "build", maxIterations = 2)
        val retrying = task.retry("transient failure")
        val failed = retrying.retry("second failure")

        assertEquals(AutonomousTaskStatus.running, retrying.status)
        assertEquals(AutonomousTaskStatus.failed, failed.status)
        assertEquals(2, failed.iterations)
    }

    @Test
    fun safetyPolicyClampsIterationBudgetAndGatesWrites() {
        assertEquals(1, AutonomousSafetyPolicy.validateMaxIterations(-5))
        assertEquals(200, AutonomousSafetyPolicy.validateMaxIterations(999))
        assertTrue(AutonomousSafetyPolicy.requiresApproval(destructive = false, externalWrite = true))
    }
}
