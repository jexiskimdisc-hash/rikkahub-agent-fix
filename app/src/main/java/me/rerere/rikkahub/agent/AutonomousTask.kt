package me.rerere.rikkahub.agent

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** A bounded plan step surfaced to the UI while an autonomous task is running. */
@Serializable
data class AutonomousStep(
    val id: String = Uuid.random().toString(),
    val title: String,
    val description: String = "",
    val status: StepStatus = StepStatus.pending,
    val attempts: Int = 0,
    val lastObservation: String? = null,
    val lastError: String? = null,
) {
    fun started(): AutonomousStep = copy(status = StepStatus.running, attempts = attempts + 1)
    fun observed(result: String): AutonomousStep = copy(
        status = StepStatus.evaluating,
        lastObservation = result.take(MAX_OBSERVATION_LENGTH),
        lastError = null,
    )
    fun succeeded(result: String? = lastObservation): AutonomousStep = copy(
        status = StepStatus.succeeded,
        lastObservation = result?.take(MAX_OBSERVATION_LENGTH),
        lastError = null,
    )
    fun failed(error: String, retryable: Boolean): AutonomousStep = copy(
        status = if (retryable) StepStatus.retrying else StepStatus.failed,
        lastError = error.take(MAX_ERROR_LENGTH),
    )

    companion object {
        private const val MAX_OBSERVATION_LENGTH = 2_000
        private const val MAX_ERROR_LENGTH = 500
    }
}

@Serializable
enum class StepStatus { pending, running, evaluating, retrying, awaiting_approval, succeeded, failed, cancelled }

@Serializable
enum class AutonomousTaskStatus { planning, running, paused, awaiting_approval, succeeded, failed, cancelled }

/** Persistable state for a task. It deliberately contains no credentials or tool arguments. */
@Serializable
data class AutonomousTask(
    val id: String = Uuid.random().toString(),
    val goal: String,
    val status: AutonomousTaskStatus = AutonomousTaskStatus.planning,
    val steps: List<AutonomousStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    val iterations: Int = 0,
    val lastError: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
) {
    val currentStep: AutonomousStep?
        get() = steps.getOrNull(currentStepIndex)

    val isTerminal: Boolean
        get() = status == AutonomousTaskStatus.succeeded ||
            status == AutonomousTaskStatus.failed ||
            status == AutonomousTaskStatus.cancelled

    fun withPlan(plan: List<AutonomousStep>, nowMs: Long = System.currentTimeMillis()) = copy(
        status = AutonomousTaskStatus.running,
        steps = plan,
        currentStepIndex = 0,
        updatedAtMs = nowMs,
        lastError = null,
    )

    fun updateStep(updated: AutonomousStep, nowMs: Long = System.currentTimeMillis()): AutonomousTask {
        val index = steps.indexOfFirst { it.id == updated.id }
        if (index < 0) return this
        return copy(steps = steps.toMutableList().also { it[index] = updated }, updatedAtMs = nowMs)
    }

    fun advance(nowMs: Long = System.currentTimeMillis()): AutonomousTask {
        val next = currentStepIndex + 1
        return if (next >= steps.size) {
            copy(status = AutonomousTaskStatus.succeeded, currentStepIndex = steps.size, updatedAtMs = nowMs)
        } else {
            copy(currentStepIndex = next, iterations = iterations + 1, updatedAtMs = nowMs)
        }
    }

    fun retry(error: String, nowMs: Long = System.currentTimeMillis()): AutonomousTask = copy(
        status = if (iterations + 1 >= maxIterations) AutonomousTaskStatus.failed else AutonomousTaskStatus.running,
        iterations = iterations + 1,
        lastError = error.take(500),
        updatedAtMs = nowMs,
    )

    fun cancel(nowMs: Long = System.currentTimeMillis()) = copy(
        status = AutonomousTaskStatus.cancelled,
        updatedAtMs = nowMs,
    )

    companion object { const val DEFAULT_MAX_ITERATIONS = 40 }
}

/** Safety gate shared by UI and execution surfaces. */
object AutonomousSafetyPolicy {
    fun validateMaxIterations(value: Int): Int = value.coerceIn(1, 200)
    fun requiresApproval(destructive: Boolean, externalWrite: Boolean): Boolean = destructive || externalWrite
}
