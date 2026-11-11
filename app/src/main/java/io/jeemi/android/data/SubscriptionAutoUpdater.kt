package io.jeemi.android.data

import io.jeemi.android.JeemiApplication
import io.jeemi.android.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Application-owned, but downloads run only while a Jeemi Activity is visible. */
internal class SubscriptionAutoUpdater(
    private val app: JeemiApplication,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uiVisible: StateFlow<Boolean> = app.uiVisible,
    private val fetcherFactory: () -> SubscriptionFetcher = app::subscriptionFetcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initialized = MutableStateFlow(false)
    private val pauses = MutableStateFlow(0)
    private val attempts = mutableMapOf<String, SubscriptionUpdateAttempt>()
    private val mutableErrors = MutableStateFlow<Map<String, SubscriptionUpdateError>>(emptyMap())
    val errors = mutableErrors.asStateFlow()

    init {
        scope.launch {
            combine(uiVisible, initialized, pauses) { visible, ready, paused ->
                visible && ready && paused == 0
            }.distinctUntilChanged().collectLatest { active ->
                if (active) while (currentCoroutineContext().isActive) {
                    updateDue()
                    delay(15_000)
                }
            }
        }
    }

    fun initialized() { initialized.value = true }
    fun pause(): AutoCloseable {
        pauses.update { it + 1 }
        val released = java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable { if (released.compareAndSet(false, true)) pauses.update { it - 1 } }
    }
    internal fun close() { scope.cancel() }
    // Caller holds the shared mutation lock; a successful manual edit clears stale errors.
    internal fun reconcile(library: Library) { prune(library) }

    private fun prune(library: Library) {
        attempts.entries.removeAll { (id, attempt) -> library.subscriptions.none { it.id == id && attempt.matches(it) } }
        mutableErrors.value = attempts.mapValues { it.value.error }
    }

    /** Never queue behind a foreground transaction. Re-read selection after locking. */
    internal suspend fun updateDue() {
        val repository = app.repository
        if (!repository.mutations.tryLock()) return
        var item: Subscription? = null
        var failure = SubscriptionUpdateError.DOWNLOAD
        try {
            val before = withContext(Dispatchers.IO) { repository.load() }
            prune(before)
            val selected = before.selected ?: return
            if (!subscriptionUpdateDue(selected, attempts[selected.id], clock())) return
            item = selected
            val fetcher = fetcherFactory()
            val body = coroutineScope {
                val download = async(Dispatchers.IO) { fetcher.fetch(selected.sourceUrl) }
                try { download.await() } finally { fetcher.cancel() }
            }
            failure = SubscriptionUpdateError.VALIDATION
            val next = withContext(Dispatchers.IO) {
                val preferences = app.engine.normalizePreferences(before.preferences)
                val parsed = app.engine.normalize(selected.name, body)
                var refreshed = selected.withFetchedContent(parsed, fetcher.usage, clock())
                app.engine.validateResources(before.resources)
                val candidate = app.engine.project(refreshed, preferences, before.resources, before.chainLibrary)
                // An unattended update cannot discard a user's explicit fallback choice.
                require(!candidate.fallbackReset)
                ChainCandidateValidator(app).use { it.validate(candidate.yaml) }
                refreshed = refreshed.copy(ruleProviderCount = candidate.providers.size)
                before.copy(subscriptions = before.subscriptions.map { if (it.id == selected.id) refreshed else it })
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable + Dispatchers.IO) { repository.save(next) }
            attempts.remove(selected.id)
            prune(next)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // A disconnected child can surface IOException instead of the
            // parent's cancellation. Never turn a pause/stop into a failure.
            currentCoroutineContext().ensureActive()
            item?.let {
                attempts[it.id] = SubscriptionUpdateAttempt(it.sourceUrl, it.updateIntervalMinutes, it.updatedAt, clock(), failure)
                mutableErrors.value = attempts.mapValues { entry -> entry.value.error }
            }
        } finally { repository.mutations.unlock() }
    }
}
