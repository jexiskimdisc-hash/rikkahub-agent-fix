package me.rerere.rikkahub.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates a genuine goal -> plan -> act -> observe -> evaluate loop.
 *
 * Planning and tool execution are injected so this coordinator can reuse ChatService/LocalTools
 * without duplicating their provider and approval machinery. The coordinator owns only lifecycle,
 * bounded retries, progress, and task state; callers persist snapshots at the callback boundary.
 */
class AutonomousTaskCoordinator(
    private val scope: CoroutineScope,
    private val planner: suspend (String) -> List<AutonomousStep>,
    private val actuator: suspend (AutonomousTask, AutonomousStep) -> ActionResult,
    private val persist: suspend (AutonomousTask) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AutonomousTask?>(null)
    val state: StateFlow<AutonomousTask?> = _state.asStateFlow()
    private var job: Job? = null

    fun start(goal: String, maxIterations: Int = AutonomousTask.DEFAULT_MAX_ITERATIONS) {
        check(goal.isNotBlank()) { "goal must not be blank" }
        job?.cancel()
        val task = AutonomousTask(
            goal = goal.trim(),
            maxIterations = AutonomousSafetyPolicy.validateMaxIterations(maxIterations),
        )
        _state.value = task
        job = scope.launch { run(task) }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it?.cancel() }
    }

    suspend fun resume() {
        val task = mutex.withLock { _state.value } ?: return
        resume(task)
    }

    /** Restore a persisted non-terminal snapshot after process recreation. */
    suspend fun resume(restored: AutonomousTask) {
        if (restored.isTerminal) {
            mutex.withLock { _state.value = restored }
            return
        }
        val task = restored.copy(status = AutonomousTaskStatus.running)
        mutex.withLock { _state.value = task }
        job?.cancel()
        job = scope.launch { run(task.copy(status = AutonomousTaskStatus.running)) }
    }

    private suspend fun run(initial: AutonomousTask) {
        var task = initial
        try {
            if (task.steps.isEmpty()) {
                task = task.withPlan(planner(task.goal))
                require(task.steps.isNotEmpty()) { "planner returned an empty plan" }
                publish(task)
            }
            while (!task.isTerminal) {
                ensureActive()
                if (task.iterations >= task.maxIterations) {
                    task = task.copy(status = AutonomousTaskStatus.failed, lastError = "iteration limit reached")
                    publish(task)
                    return
                }
                val step = task.currentStep ?: run {
                    task = task.copy(status = AutonomousTaskStatus.succeeded)
                    publish(task)
                    return
                }
                if (step.status == StepStatus.succeeded) {
                    task = task.advance()
                    publish(task)
                    continue
                }
                task = task.updateStep(step.started())
                publish(task)
                when (val result = actuator(task, step)) {
                    is ActionResult.NeedsApproval -> {
                        task = task.copy(status = AutonomousTaskStatus.awaiting_approval)
                            .updateStep(step.copy(status = StepStatus.awaiting_approval))
                        publish(task)
                        return
                    }
                    is ActionResult.Success -> {
                        task = task.updateStep(step.observed(result.observation))
                            .updateStep(step.succeeded(result.observation))
                            .advance()
                    }
                    is ActionResult.RetryableFailure -> {
                        task = task.updateStep(step.failed(result.error, retryable = true)).retry(result.error)
                    }
                    is ActionResult.FatalFailure -> {
                        task = task.updateStep(step.failed(result.error, retryable = false))
                            .copy(status = AutonomousTaskStatus.failed, lastError = result.error.take(500))
                    }
                }
                publish(task)
            }
        } catch (cancelled: CancellationException) {
            task = task.cancel()
            publish(task)
            throw cancelled
        } catch (error: Throwable) {
            task = task.copy(status = AutonomousTaskStatus.failed, lastError = error.message ?: error.javaClass.simpleName)
            publish(task)
        }
    }

    private suspend fun publish(task: AutonomousTask) {
        mutex.withLock { _state.value = task }
        persist(task)
    }

    private fun ensureActive() {
        if (job?.isActive == false) throw CancellationException("autonomous task cancelled")
    }
}

sealed interface ActionResult {
    data class Success(val observation: String) : ActionResult
    data class RetryableFailure(val error: String) : ActionResult
    data class FatalFailure(val error: String) : ActionResult
    data class NeedsApproval(val reason: String) : ActionResult
}
