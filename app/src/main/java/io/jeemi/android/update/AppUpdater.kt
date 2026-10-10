package io.jeemi.android.update

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Cancelled : AppUpdateState
    data class Working(val progress: UpdateProgress) : AppUpdateState
    data class Current(val latest: String) : AppUpdateState
    data class Failed(val problem: UpdateProblem) : AppUpdateState
    data class Ready(val update: DownloadedUpdate, val requestInstall: Boolean = true,
        val problem: UpdateProblem? = null) : AppUpdateState
}

/** Main-thread actions; separate from configuration saves and the VPN session. */
internal class AppUpdater(private val scope: CoroutineScope, private val source: AppUpdateSource) {
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun check() = work {
        when (val result = source.prepare { mutableState.value = AppUpdateState.Working(it) }) {
            is UpdateResult.Current -> AppUpdateState.Current(result.latest)
            is UpdateResult.Downloaded -> AppUpdateState.Ready(result.update)
        }
    }
    fun install() {
        val ready = mutableState.value as? AppUpdateState.Ready ?: return
        work(UpdateProgress(UpdatePhase.VERIFYING, ready.update.release.version.toString())) {
            source.verify(ready.update)
            AppUpdateState.Ready(ready.update)
        }
    }
    private fun work(progress: UpdateProgress = UpdateProgress(UpdatePhase.CHECKING), action: suspend () -> AppUpdateState) {
        if (job != null) return
        mutableState.value = AppUpdateState.Working(progress)
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = action()
                ensureActive()
                mutableState.value = result
            } catch (cancelled: CancellationException) {
                mutableState.value = AppUpdateState.Cancelled
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = if (!isActive) AppUpdateState.Cancelled else AppUpdateState.Failed(
                    (failure as? UpdateFailure)?.problem ?: if (failure is java.io.IOException) UpdateProblem.NETWORK else UpdateProblem.RELEASE)
            } finally { job = null }
        }
        job = next
        next.start()
    }
    fun cancel() { source.cancel(); job?.cancel() }
    fun leaveForeground() {
        cancel()
        val ready = mutableState.value as? AppUpdateState.Ready
        if (ready != null) mutableState.value = ready.copy(requestInstall = false)
    }
    fun takeInstallRequest(): DownloadedUpdate? {
        val ready = mutableState.value as? AppUpdateState.Ready ?: return null
        if (!ready.requestInstall) return null
        mutableState.value = ready.copy(requestInstall = false)
        return ready.update
    }
    fun installationProblem(problem: UpdateProblem) {
        val ready = mutableState.value as? AppUpdateState.Ready ?: return
        mutableState.value = ready.copy(requestInstall = false, problem = problem)
    }
}
