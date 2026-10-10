// Author: Bluevava
// Open-source repository: https://github.com/bluevava/jeemi-android

package io.jeemi.android.ui

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.jeemi.android.JeemiApplication
import io.jeemi.android.data.*
import io.jeemi.android.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mobile.Mobile
import org.json.JSONObject
import org.json.JSONArray
import io.jeemi.android.runtime.VpnRequest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

enum class AppError { LOAD, IMPORT, SAVE, PREVIEW, RESOURCE, NETWORK, EXPORT, CORE, DASHBOARD, SCRIPT_DOWNLOAD }
data class AppState(
    val library: Library = Library(), val loaded: Boolean = false,
    val busy: Boolean = false, val error: AppError? = null,
    val preview: String? = null, val importRevision: Int = 0, val saveRevision: Int = 0,
    val structure: SubscriptionStructure = SubscriptionStructure(), val catalog: List<ConfigField> = emptyList(),
    val coreVersion: String? = null, val coreChecking: Boolean = false, val coreCheckFailed: Boolean = false,
    val dnsResult: String? = null, val dnsBusy: Boolean = false, val downloadBusy: Boolean = false,
    val previewTitle: Int = io.jeemi.android.R.string.configuration_preview,
    val candidate: Candidate? = null, val projectionFailed: Boolean = false,
    val packagePreview: PackagePreview? = null,
    val geo: List<GeoAsset> = emptyList(),
    val testing: Boolean = false,
    val testingNodes: Set<String> = emptySet(),
    val chains: ChainState = ChainState(), val chainBusy: Boolean = false,
    val chainFailures: List<ChainFailure> = emptyList(), val businessIssue: BusinessIssue? = null,
    val conversionReport: String? = null,
    val chainComposition: String? = null,
    val resourceDownloading: Boolean = false, val dashboardDownloading: Boolean = false,
    val dashboardPrepared: Boolean = false, val dashboardRestartSession: String? = null,
    val resourceGraph: ResourceGraph = ResourceGraph(), val resourceCheck: ResourceCheck? = null,
)
internal val AppState.geoRevision: String get() = geo.joinToString(":") { it.sha256 }
data class PackagePreview(val resources: List<LocalResource>, val target: LocalResource,
    val overwritten: List<String>, val added: List<String>, val affected: List<String>, val revision: Int)
data class RuntimeDraftIssue(val field: String, val line: Int)
private class InvalidRuntimeDraft(val issue: RuntimeDraftIssue) : Exception()

class JeemiViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as JeemiApplication
    internal val appUpdater = io.jeemi.android.update.AppUpdater(viewModelScope, io.jeemi.android.update.AppUpdateRepository(app))
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    // Large documents stay out of the saved-state Bundle.
    val importDraft = MutableStateFlow("")
    val editingResource = MutableStateFlow<LocalResource?>(null)
    internal val installedApps by lazy { InstalledAppsCatalog(app.packageManager) }
    internal val ruleAppDetails by lazy { InstalledAppDetails(app.packageManager) }
    val scriptInput = MutableStateFlow("")
    val selectorPaths = MutableStateFlow<Map<List<String>, List<String>>>(emptyMap())
    fun setSelectorPath(key: List<String>, names: List<String>) {
        val retained = selectorPaths.value.filterKeys { it != key }.entries.toList().takeLast(127).associate { it.toPair() }
        selectorPaths.value = retained + (key to names)
    }
    private var editingOriginal: LocalResource? = null
    private var resourceNetwork: Job? = null
    private var resourceRequest: mobile.ResourceDownload? = null
    private var resourceGeneration = 0
    private var dashboardNetwork: Job? = null
    private var dashboardRequest: mobile.ResourceDownload? = null
    private var dashboardGeneration = 0
    val runtimeDraft = MutableStateFlow<String?>(null)
    val runtimeTextDraft = MutableStateFlow<Map<String, String>>(emptyMap())
    val runtimeIssue = MutableStateFlow<RuntimeDraftIssue?>(null)
    val fieldDraft = MutableStateFlow("")
    val chainDraft = MutableStateFlow<ChainDraft?>(null)
    private var chainNetwork: Job? = null
    @Volatile private var chainFetcher: SubscriptionFetcher? = null
    @Volatile private var chainGeneration = 0
    private var exportDraft: String? = null
    private var activeDownload: SubscriptionFetcher? = null
    val runtime = app.runtime.state
    val live = app.runtime.live
    val diagnostics = io.jeemi.android.runtime.Diagnostics(app.runtime, viewModelScope, app)
    private var tests: Job? = null
    private val singleTests = mutableMapOf<String, Job>()
    private val icons = SubscriptionIcons()
    internal val selectorIcons get() = app.selectorIcons
    private val mutations = Mutex()
    private var vpnActionRevision = 0

    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val stored = synchronized(app.repository) {
                        val previous = app.repository.load()
                        withBundledAppRouting(previous, app, app.engine).also {
                            if (it != previous) app.repository.save(it)
                        }
                    }
                    val normalized = stored.copy(preferences = app.engine.normalizePreferences(stored.preferences),
                        subscriptions = stored.subscriptions.map { runCatching { app.engine.reparse(it) }.getOrDefault(it) })
                    val library = normalized.copy(subscriptions = normalized.subscriptions.map { item ->
                        item.copy(ruleProviderCount = runCatching { app.engine.project(item, normalized.preferences, normalized.resources, normalized.chainLibrary).providers.size }
                            .getOrDefault(item.ruleProviderCount))
                    })
                    val candidate = runCatching { project(library) }
                    AppState(library, loaded = true, structure = candidate.getOrNull()?.structure ?: SubscriptionStructure(),
                        catalog = app.engine.catalog(), candidate = candidate.getOrNull(), projectionFailed = candidate.isFailure,
                        geo = app.geodata.assets(), chains = chainState(library.chainLibrary), resourceGraph = runCatching { resourceGraph(library) }.getOrDefault(ResourceGraph(available = false)))
                }
                mutableState.value = loaded
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.value = AppState(error = AppError.LOAD) }
        }
    }

    private fun project(library: Library) = library.selected?.let { app.engine.project(it, library.preferences, library.resources, library.chainLibrary) }
    fun dismissError() { mutableState.value = mutableState.value.copy(error = null, businessIssue = null) }
    fun startVpn() = requestVpn(restart = false)
    fun restartVpn() = requestVpn(restart = true)
    private fun requestVpn(restart: Boolean) {
        val actionRevision = ++vpnActionRevision
        work(AppError.CORE) {
            check(!mutableState.value.projectionFailed)
            val current = mutableState.value.library
            val selected = requireNotNull(current.selected)
            val candidate = requireNotNull(mutableState.value.candidate)
            val request = VpnRequest(selected.id, candidate.yaml, JSONObject(current.preferences.runtimeJson).getBoolean("ipv6"),
                revision = candidate.revision,
                logLevel = JSONObject(current.preferences.runtimeJson).getString("logLevel"), selections = selected.selections,
                defaults = candidate.structure.groups.associate { it.name to it.defaultSelected },
                tunStack = current.preferences.tunStack,
                externalUIVersion = current.preferences.externalUIVersion.takeIf { current.preferences.externalUIEnabled }.orEmpty())
            withContext(Dispatchers.Main) {
                // Stopping also invalidates a request still being prepared off the UI thread.
                if (actionRevision != vpnActionRevision) return@withContext
                if (restart) {
                    tests?.cancel(); singleTests.values.toList().forEach { it.cancel() }
                    app.runtime.restart(request)
                } else app.runtime.start(request)
            }
        }
    }
    fun stopVpn() {
        vpnActionRevision++
        tests?.cancel(); singleTests.values.toList().forEach { it.cancel() }
        try { app.runtime.stop() }
        catch (_: Exception) { mutableState.value = mutableState.value.copy(error = AppError.CORE) }
    }
    fun selectNode(group: String, node: String) = commit(AppError.SAVE) { library ->
        val current = mutableState.value
        val selected = requireNotNull(library.selected)
        val candidate = requireNotNull(current.candidate)
        val manual = candidate.structure.groups.first { it.name == group && it.type == "select" }
        if (app.runtime.matches(selected.id, candidate.revision, current.geoRevision)) {
            app.runtime.select(selected.id, candidate.revision, current.geoRevision, group, node, library.preferences.connectionReset)
        } else {
            require(!runtime.value.transitioning)
            require(node in manual.members)
        }
        library.copy(subscriptions = library.subscriptions.map {
            if (it.id == selected.id) it.copy(selections = it.selections + (group to node)) else it
        })
    }
    fun refreshProvider(name: String) = work(AppError.NETWORK) {
        val current = mutableState.value
        app.runtime.refreshProvider(requireNotNull(current.library.selectedId), requireNotNull(current.candidate).revision,
            current.geoRevision, name)
    }
    fun testNodes(names: List<String>) {
        if (tests?.isActive == true) { tests?.cancel(); return }
        if (singleTests.isNotEmpty()) return
        val current = mutableState.value
        val profile = current.library.selectedId ?: return
        val revision = current.candidate?.revision ?: return
        if (!app.runtime.matches(profile, revision, current.geoRevision)) return
        tests = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(testing = true)
            try {
                val semaphore = Semaphore(current.library.preferences.testConcurrency)
                coroutineScope {
                    app.runtime.live.value?.proxies.orEmpty().filter { (name, value) ->
                        name in names && value.members.isEmpty() && value.type !in listOf("Direct", "Reject", "Pass")
                    }.keys.map { name -> async(Dispatchers.IO) {
                        semaphore.withPermit {
                            ensureActive()
                            if (app.runtime.matches(profile, revision, current.geoRevision))
                                try {
                                    withContext(Dispatchers.Main) { mutableState.value = mutableState.value.copy(testingNodes = mutableState.value.testingNodes + name) }
                                    app.runtime.test(profile, revision, current.geoRevision, name)
                                } finally {
                                    withContext(NonCancellable + Dispatchers.Main) { mutableState.value = mutableState.value.copy(testingNodes = mutableState.value.testingNodes - name) }
                                }
                        }
                    } }.awaitAll()
                }
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = AppError.NETWORK)
            } finally { mutableState.value = mutableState.value.copy(testing = false) }
        }
    }
    fun testNode(name: String) {
        val current = mutableState.value
        if (current.testing || name in singleTests) return
        val profile = current.library.selectedId ?: return
        val revision = current.candidate?.revision ?: return
        if (!app.runtime.matches(profile, revision, current.geoRevision)) return
        val proxy = live.value?.proxies?.get(name) ?: return
        if (proxy.members.isNotEmpty() || proxy.type.lowercase() in listOf("direct", "reject", "pass")) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            mutableState.value = mutableState.value.copy(testingNodes = mutableState.value.testingNodes + name)
            try { withContext(Dispatchers.IO) { app.runtime.test(profile, revision, current.geoRevision, name) } }
            catch (_: CancellationException) { }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(error = AppError.NETWORK) }
            finally {
                singleTests.remove(name)
                mutableState.value = mutableState.value.copy(testingNodes = mutableState.value.testingNodes - name)
            }
        }
        singleTests[name] = job; job.start()
    }
    suspend fun detectSubscriptionIcon(url: String): String? = withContext(Dispatchers.IO) { icons.detect(url)?.url }
    fun dismissPreview() { mutableState.value = mutableState.value.copy(preview = null) }
    fun viewDocument(text: String, title: Int = io.jeemi.android.R.string.configuration_preview) { mutableState.value = mutableState.value.copy(preview = text, previewTitle = title) }
    fun prepareExport() { exportDraft = mutableState.value.preview }
    fun export(uri: Uri) = work(AppError.EXPORT) {
        val data = requireNotNull(exportDraft).toByteArray(Charsets.UTF_8)
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) } ?: error("cannot_write_document")
        exportDraft = null
    }

    fun savePreferences(preferences: Preferences, onSuccess: () -> Unit = {}) = commit(AppError.SAVE, onSuccess = onSuccess) {
        if (preferences.geoMode == "dat") require(app.geodata.assets().any { asset -> asset.kind == "geoip-dat" })
        it.copy(preferences = app.engine.normalizePreferences(preferences))
    }
    fun beginRuntimeEdit() {
        runtimeDraft.value = state.value.library.preferences.runtimeJson
        runtimeTextDraft.value = emptyMap(); runtimeIssue.value = null
    }
    fun closeRuntimeEdit() {
        runtimeDraft.value = null; runtimeTextDraft.value = emptyMap(); runtimeIssue.value = null
    }
    fun updateRuntimeValue(key: String, value: Any) {
        runtimeDraft.value = JSONObject(runtimeDraft.value ?: state.value.library.preferences.runtimeJson).put(key, value).toString()
    }
    fun updateRuntimeText(field: String, value: String) { runtimeTextDraft.value = runtimeTextDraft.value + (field to value) }
    fun saveRuntimePreferences(onSuccess: () -> Unit) {
        val raw = runtimeDraft.value ?: state.value.library.preferences.runtimeJson
        val edits = JSONObject(runtimeTextDraft.value).toString()
        runtimeIssue.value = null
        commit(AppError.SAVE, onSuccess = onSuccess) { library ->
            val result = JSONObject(Mobile.applyRuntimeDrafts(raw, edits))
            result.optJSONObject("issue")?.let { throw InvalidRuntimeDraft(RuntimeDraftIssue(it.getString("field"), it.optInt("line"))) }
            library.copy(preferences = app.engine.normalizePreferences(library.preferences.copy(runtimeJson = result.getJSONObject("preferences").toString())))
        }
    }
    fun addRuntimePreset(field: String, text: String, value: String) {
        work(AppError.SAVE) {
            var result = JSONObject(Mobile.validateRuntimeFragment(field,
                if (text.isBlank()) runtimeStringSequence(listOf(value)) else text))
            if (result.getBoolean("valid")) {
                val values = result.getJSONArray("values").let { array -> (0 until array.length()).map { array.getString(it) } }
                result = JSONObject(Mobile.validateRuntimeFragment(field, runtimeStringSequence((values + value).distinct())))
            }
            if (!result.getBoolean("valid")) {
                withContext(Dispatchers.Main) { runtimeIssue.value = RuntimeDraftIssue(field, result.getJSONObject("issue").optInt("line")) }
            } else {
                val yaml = result.getString("normalizedYaml")
                withContext(Dispatchers.Main) { updateRuntimeText(field, yaml); runtimeIssue.value = null }
            }
        }
    }
    fun select(id: String) = commit(AppError.SAVE) { it.selecting(id) }
    fun remove(id: String) = commit(AppError.SAVE) { it.removing(id) }
    fun associate(id: String, handler: String?, resources: List<String>, expected: Subscription? = null,
        onSuccess: () -> Unit = {}) = commit(AppError.SAVE, onSuccess = onSuccess) {
        if (expected != null) check(it.subscriptions.firstOrNull { sub -> sub.id == id } == expected)
        it.associating(id, handler, resources)
    }
    fun unlinkHandler(subscription: Subscription, kind: ResourceKind) = commit(AppError.SAVE) { old ->
        val current = old.subscriptions.first { it.id == subscription.id }
        check(current == subscription)
        require(old.resources.any { it.id == current.handlerId && it.kind == kind })
        old.associating(current.id, null, current.resourceIds)
    }
    fun removeResource(id: String) = commit(AppError.SAVE) { it.removingResource(id) }
    fun fallback(mode: String, selector: String, onSuccess: () -> Unit) = commit(AppError.SAVE, onSuccess = onSuccess) { library ->
        library.copy(subscriptions = library.subscriptions.map {
            if (it.id == library.selectedId) it.copy(fallbackMode = mode, fallbackSelector = selector) else it
        })
    }
    fun provider(name: String, enabled: Boolean) = commit(AppError.SAVE) { library ->
        library.copy(subscriptions = library.subscriptions.map {
            if (it.id == library.selectedId) it.copy(disabledProviders =
                if (enabled) it.disabledProviders - name else (it.disabledProviders + name).distinct()) else it
        })
    }

    fun openResource(resource: LocalResource) {
        cancelResourceNetwork()
        editingOriginal = mutableState.value.library.resources.firstOrNull { it.id == resource.id }
        editingResource.value = resource
        scriptInput.value = resource.sourceUrl.ifBlank { resource.content }
    }
    fun closeResource() { cancelResourceNetwork(); editingResource.value = null; editingOriginal = null; fieldDraft.value = ""; scriptInput.value = "" }

    internal fun editRuleApps(expected: LocalResource, request: JSONObject, onSuccess: () -> Unit = {}) = work(AppError.RESOURCE, onSuccess) {
        val content = JSONObject(expected.content)
        require(content.isAppListEnabled())
        val updated = editAppRuleDraft(content.getString("payloadYaml"), request)
        withContext(Dispatchers.Main) {
            check(editingResource.value == expected)
            editingResource.value = expected.copy(content = content.put("payloadYaml", updated.yaml).toString())
        }
    }
    fun cancelResourceNetwork() {
        resourceGeneration++; resourceRequest?.cancel(); resourceNetwork?.cancel()
        mutableState.value = mutableState.value.copy(resourceDownloading = false)
    }
    private fun scriptOperation(error: AppError = AppError.SCRIPT_DOWNLOAD,
        action: suspend (mobile.ResourceDownload, Int) -> Unit) {
        if (resourceNetwork?.isActive == true) return
        val generation = ++resourceGeneration
        val request = Mobile.newResourceDownload()
        resourceRequest = request
        mutableState.value = mutableState.value.copy(resourceDownloading = true, error = null, businessIssue = null)
        resourceNetwork = viewModelScope.launch {
            try { action(request, generation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == resourceGeneration && isActive) mutableState.value = mutableState.value.copy(error = error)
            }
            finally {
                request.cancel()
                if (generation == resourceGeneration) {
                    resourceRequest = null
                    mutableState.value = mutableState.value.copy(resourceDownloading = false)
                }
            }
        }
    }
    private suspend fun resolveScript(draft: LocalResource, input: String, request: mobile.ResourceDownload,
        refresh: Boolean = false): LocalResource = withContext(Dispatchers.IO) {
        if (!isScriptUrl(input)) return@withContext draft.copy(content = input, sourceUrl = "")
        val address = Mobile.normalizeScriptURL(input)
        if (!refresh && address == draft.sourceUrl) draft
        else draft.copy(content = request.script(address), sourceUrl = address)
    }
    fun refreshScript(id: String) {
        val expected = mutableState.value.library.resources.firstOrNull { it.id == id && it.kind == ResourceKind.SCRIPT && it.sourceUrl.isNotEmpty() } ?: return
        scriptOperation { request, generation ->
            val prepared = resolveScript(expected, expected.sourceUrl, request, refresh = true)
            commit(AppError.RESOURCE, valid = { generation == resourceGeneration }) { old ->
                check(old.resources.firstOrNull { it.id == id } == expected)
                old.copy(resources = old.resources.replacing(app.engine.prepareTyped(prepared)))
            }.join()
        }
    }
    fun saveResource() {
        val draft = editingResource.value ?: return
        val expected = editingOriginal
        if (draft.kind == ResourceKind.SCRIPT) {
            val input = scriptInput.value
            scriptOperation { request, generation ->
                require(draft.name.isNotBlank() && draft.name.length <= 80)
                val prepared = resolveScript(draft, input, request)
                commit(AppError.RESOURCE, valid = { generation == resourceGeneration },
                    onSuccess = { editingResource.value = null; scriptInput.value = ""; editingOriginal = null }) { old ->
                    check(old.resources.firstOrNull { it.id == draft.id } == expected)
                    require(old.resources.size < 100 || expected != null)
                    old.copy(resources = old.resources.replacing(app.engine.prepareTyped(prepared)))
                }.join()
            }
            return
        }
        commit(AppError.RESOURCE, onSuccess = ::closeResource) { old ->
            check(old.resources.firstOrNull { it.id == draft.id } == expected)
            require(draft.name.isNotBlank() && draft.name.length <= 80)
            require(old.resources.size < 100 || old.resources.any { it.id == draft.id })
            val previous = old.resources.firstOrNull { it.id == draft.id }
            if (previous?.kind == ResourceKind.GROUPS && previous.formatVersion >= 2)
                require(JSONObject(previous.content).getString("kind") == JSONObject(draft.content).getString("kind"))
            val prepared = app.engine.prepareTyped(draft)
            old.copy(resources = old.resources.replacing(prepared))
        }
    }
    fun addField(field: ConfigField, value: String, enabled: Boolean = true,
        strategy: String = field.defaultStrategy, conflict: String = field.defaultConflict,
        onSuccess: () -> Unit) {
        val draft = editingResource.value ?: return
        work(AppError.RESOURCE, onSuccess) {
            require(!field.locked && !field.hidden)
            val updated = if (draft.formatVersion < 2) Mobile.setConfigurationField(draft.content.ifBlank { "{}" }, field.path, value)
            else {
                val content = JSONObject(draft.content)
                val entry = JSONObject().put("path", field.path).put("valueYaml", value).put("strategy", strategy).put("conflictPolicy", conflict)
                for (key in listOf("fields", "disabledFields")) {
                    val fields = content.optJSONArray(key) ?: JSONArray()
                    val filtered = JSONArray()
                    repeat(fields.length()) { if (fields.getJSONObject(it).getString("path") != field.path) filtered.put(fields.getJSONObject(it)) }
                    // Validate the edited value even when keeping its injection disabled.
                    if (key == "fields") filtered.put(entry)
                    content.put(key, filtered)
                }
                // Validate the complete field draft without committing its resource references.
                val validated = app.engine.prepareTyped(draft.copy(name = draft.name.ifBlank { "draft" }, content = content.toString())).content
                JSONObject(validated).setConfigFieldEnabled(setOf(field.path), enabled).toString()
            }
            withContext(Dispatchers.Main) {
                check(editingResource.value == draft)
                editingResource.value = draft.copy(content = updated)
            }
        }
    }
    fun toggleFields(fields: List<ConfigField>, enabled: Boolean) {
        val draft = editingResource.value ?: return
        if (fields.isEmpty()) return
        work(AppError.RESOURCE) {
            require(draft.formatVersion >= 2 && fields.none { it.locked || it.hidden })
            val content = JSONObject(draft.content)
            val configured = (content.configFields("fields") + content.configFields("disabledFields")).map { it.getString("path") }.toSet()
            if (enabled) {
                val active = content.optJSONArray("fields") ?: JSONArray()
                fields.filterNot { it.path in configured }.forEach { field ->
                    active.put(JSONObject().put("path", field.path).put("valueYaml", field.example)
                        .put("strategy", field.defaultStrategy).put("conflictPolicy", field.defaultConflict))
                }
                content.put("fields", active)
            }
            content.setConfigFieldEnabled(fields.map { it.path }.toSet(), enabled)
            val updated = app.engine.prepareTyped(draft.copy(name = draft.name.ifBlank { "draft" }, content = content.toString())).content
            withContext(Dispatchers.Main) {
                check(editingResource.value == draft)
                editingResource.value = draft.copy(content = updated)
            }
        }
    }
    fun removeField(path: String) {
        val draft = editingResource.value ?: return
        val content = JSONObject(draft.content)
        listOf("fields", "disabledFields").forEach { key ->
            val array = content.optJSONArray(key) ?: JSONArray()
            content.put(key, JSONArray().apply { repeat(array.length()) {
                if (array.getJSONObject(it).getString("path") != path) put(array.getJSONObject(it))
            } })
        }
        editingResource.value = draft.copy(content = content.toString())
    }
    fun dismissResourceCheck() { mutableState.value = mutableState.value.copy(resourceCheck = null) }
    private suspend fun checkResourceCandidates(library: Library, key: String) {
        app.engine.validateResources(library.resources)
        val graph = resourceGraph(library)
        val affected = graph.affected(key).toSet()
        val rows = ChainCandidateValidator(app).use { validator ->
            library.subscriptions.filter { it.id in affected }.map { subscription ->
                currentCoroutineContext().ensureActive()
                try {
                    val candidate = app.engine.project(subscription, library.preferences, library.resources, library.chainLibrary)
                    validator.validate(candidate.yaml)
                    CandidateCheck(subscription.id, subscription.name, true)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    CandidateCheck(subscription.id, subscription.name, false, businessIssue(failure))
                }
            }
        }
        withContext(Dispatchers.Main) {
            mutableState.value = mutableState.value.copy(resourceCheck = ResourceCheck(graph, key, rows, library))
        }
    }
    fun previewCheckedCandidate(id: String) = work(AppError.PREVIEW) {
        val check = requireNotNull(mutableState.value.resourceCheck)
        require(check.candidates.any { it.id == id && it.passed })
        val library = check.library
        val subscription = library.subscriptions.first { it.id == id }
        val yaml = app.engine.project(subscription, library.preferences, library.resources, library.chainLibrary).yaml
        withContext(Dispatchers.Main) { viewDocument(yaml) }
    }
    fun previewResource(subscriptionId: String? = null) {
        val draft = editingResource.value ?: return
        val input = scriptInput.value
        val current = mutableState.value.library
        val revision = mutableState.value.saveRevision
        if (draft.kind == ResourceKind.SCRIPT) {
            scriptOperation(AppError.PREVIEW) { request, generation ->
                val resolved = resolveScript(draft, input, request)
                // Network acquisition stays outside the save lock; inspection is detached.
                mutations.withLock {
                    check(revision == mutableState.value.saveRevision && generation == resourceGeneration)
                    withContext(Dispatchers.IO) {
                        val prepared = app.engine.prepareTyped(resolved.copy(name = resolved.name.ifBlank { app.getString(resolved.kind.label) }))
                        val next = current.copy(resources = current.resources.replacing(prepared), subscriptions = current.subscriptions.map {
                            if (it.id == subscriptionId) it.copy(handlerId = prepared.id) else it
                        })
                        checkResourceCandidates(next, "resource:${prepared.id}")
                    }
                }
            }
        } else work(AppError.PREVIEW) {
            check(revision == mutableState.value.saveRevision)
            val prepared = app.engine.prepareTyped(draft.copy(name = draft.name.ifBlank { app.getString(draft.kind.label) }))
            val next = current.copy(resources = current.resources.replacing(prepared), subscriptions = current.subscriptions.map {
                if (draft.kind == ResourceKind.CONFIG && it.id == subscriptionId) it.copy(handlerId = prepared.id) else it
            })
            checkResourceCandidates(next, "resource:${prepared.id}")
        }
    }
    fun previewAssociation(subscription: Subscription, handler: String?, extras: List<String>) = work(AppError.PREVIEW) {
        val current = mutableState.value.library
        check(current.subscriptions.firstOrNull { it.id == subscription.id } == subscription)
        checkResourceCandidates(current.associating(subscription.id, handler, extras), "subscription:${subscription.id}")
    }
    fun previewChainAssociation(subscription: Subscription, groups: List<String>, revision: Int) = work(AppError.PREVIEW) {
        val current = mutableState.value.library
        check(current.subscriptions.firstOrNull { it.id == subscription.id } == subscription && revision == mutableState.value.chains.revision)
        val next = current.copy(subscriptions = current.subscriptions.map {
            if (it.id == subscription.id) it.copy(chainGroupIds = groups, chainRevision = it.chainRevision + 1) else it
        })
        checkResourceCandidates(next, "subscription:${subscription.id}")
    }
    fun previewChainGroup(id: String) = work(AppError.PREVIEW) {
        checkResourceCandidates(mutableState.value.library, "chain:$id")
    }
    fun previewSavedResource(id: String) = work(AppError.PREVIEW) {
        checkResourceCandidates(mutableState.value.library, "resource:$id")
    }
    fun importResourceFile(uri: Uri) = work(AppError.RESOURCE) {
        val draft = requireNotNull(editingResource.value)
        val text = readFile(uri, 16 * 1024 * 1024)
        if (draft.kind == ResourceKind.SCRIPT && !text.trimStart().startsWith("{")) {
            app.engine.prepareTyped(draft.copy(content = text))
            withContext(Dispatchers.Main) { editingResource.value = draft.copy(content = text, sourceUrl = ""); scriptInput.value = text }
        } else {
            val current = mutableState.value.library
            val result = JSONObject(Mobile.prepareResourceImport(text, draft.toJson().toString(), current.resources.toJson().toString()))
            val resources = result.getJSONArray("resources").let { array -> List(array.length()) { resourceFromJson(array.getJSONObject(it)) } }
            // Import preview checks every existing subscription, including unselected profiles.
            current.subscriptions.forEach { app.engine.project(it, current.preferences, resources, current.chainLibrary) }
            val changed = resources.filter { it != current.resources.firstOrNull { old -> old.id == it.id } }.map { it.id }.toSet()
            val graph = resourceGraph(current.copy(resources = resources))
            val affectedIds = changed.flatMap { graph.affected("resource:$it") }.toSet()
            val affected = current.subscriptions.filter { it.id in affectedIds }.map { it.name }
            val preview = PackagePreview(resources, resourceFromJson(result.getJSONObject("target")),
                result.getJSONArray("overwritten").strings(), result.getJSONArray("added").strings(), affected, mutableState.value.saveRevision)
            withContext(Dispatchers.Main) { mutableState.value = mutableState.value.copy(packagePreview = preview) }
        }
    }
    fun dismissPackage() { mutableState.value = mutableState.value.copy(packagePreview = null) }
    fun confirmPackage() {
        val preview = mutableState.value.packagePreview ?: return
        commit(AppError.RESOURCE, onSuccess = { dismissPackage(); closeResource() }) { old ->
            require(preview.revision == mutableState.value.saveRevision)
            old.copy(resources = preview.resources)
        }
    }
    fun exportResource() {
        val selected = editingResource.value ?: return
        if (selected.kind == ResourceKind.SCRIPT) {
            val input = scriptInput.value
            val resources = mutableState.value.library.resources
            scriptOperation(AppError.EXPORT) { request, _ ->
                val draft = resolveScript(selected, input, request)
                val result = withContext(Dispatchers.IO) { Mobile.exportResourcePackage(draft.toJson().toString(), resources.toJson().toString()) }
                currentCoroutineContext().ensureActive()
                viewDocument(result, io.jeemi.android.R.string.resource_package)
            }
            return
        }
        work(AppError.EXPORT) {
        val draft = requireNotNull(editingResource.value)
        val result = Mobile.exportResourcePackage(draft.toJson().toString(), mutableState.value.library.resources.toJson().toString())
        withContext(Dispatchers.Main) { viewDocument(result, io.jeemi.android.R.string.resource_package) }
        }
    }

    private data class ImportedContent(val text: String, val suggestedName: String = "", val usage: Map<String, Long> = emptyMap())
    fun importText(name: String, text: String, description: String = "", url: String = "", id: String? = null,
        icon: String? = null, handler: String? = null, overrideHandler: Boolean = false) =
        importSubscription(name, description, url, id, icon, handler, overrideHandler) { ImportedContent(text) }
    fun importFile(name: String, uri: Uri) = importSubscription(name, "", "", null) { ImportedContent(readFile(uri)) }
    fun importUrl(name: String, url: String, description: String = "", icon: String = "", handler: String? = null) {
        val fetcher = app.subscriptionFetcher()
        activeDownload = fetcher
        mutableState.value = mutableState.value.copy(downloadBusy = true)
        importSubscription(name, description, url, null, icon, handler, true, valid = { !fetcher.isCancelled }) {
            ImportedContent(fetcher.fetch(url), fetcher.suggestedName, fetcher.usage)
        }
    }
    fun cancelDownload() { activeDownload?.cancel(); icons.cancel() }
    fun refresh(id: String) {
        val item = mutableState.value.library.subscriptions.firstOrNull { it.id == id } ?: return
        val fetcher = app.subscriptionFetcher()
        activeDownload = fetcher
        mutableState.value = mutableState.value.copy(downloadBusy = true)
        importSubscription(item.name, item.description, item.sourceUrl, id, valid = { !fetcher.isCancelled }) {
            ImportedContent(fetcher.fetch(item.sourceUrl), fetcher.suggestedName, fetcher.usage)
        }
    }
    private fun readFile(uri: Uri, limit: Int = MAX_IMPORT_BYTES): String = app.contentResolver.openInputStream(uri)?.use {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(it.readBounded(limit))).toString()
    } ?: error("cannot_open_document")

    private fun importSubscription(name: String, description: String, url: String, id: String?,
        icon: String? = null, handler: String? = null, overrideHandler: Boolean = false,
        valid: () -> Boolean = { true }, read: () -> ImportedContent) =
        commit(AppError.IMPORT, imported = true, valid = valid) { old ->
            require(name.length <= 80 && description.length <= 500)
            if (url.isNotBlank()) requireSubscriptionUrl(url)
            require(old.subscriptions.size < 50 || id != null)
            val previous = id?.let { value -> old.subscriptions.first { it.id == value } }
            val content = read()
            val raw = content.text
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES)
            val resolvedName = importedSubscriptionName(name, content.suggestedName, old.subscriptions.map { it.name }.toSet()) {
                val locale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().get(0)
                val context = if (locale == null) app else app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply { setLocale(locale) })
                context.getString(io.jeemi.android.R.string.unnamed_subscription, it)
            }
            // Detect/normalize first; icons are optional and cannot replace a failed source.
            val normalized = app.engine.normalize(resolvedName, raw)
            var chosenIcon = (icon ?: previous?.icon ?: "").trim()
            val remoteIcon = chosenIcon.startsWith("http://", true) || chosenIcon.startsWith("https://", true)
            if (remoteIcon) requireSubscriptionUrl(chosenIcon)
            else require(chosenIcon.length <= 24 && chosenIcon.none { it.isISOControl() })
            val image = when {
                remoteIcon -> icons.load(chosenIcon)
                chosenIcon.isBlank() && url.isNotBlank() -> icons.detect(url)
                else -> null
            }
            if (chosenIcon.isBlank() && image != null) chosenIcon = image.url
            check(activeDownload?.isCancelled != true)
            val item = normalized.copy(id = previous?.id ?: UUID.randomUUID().toString(),
                description = description, sourceUrl = url.trim(), updatedAt = System.currentTimeMillis(),
                handlerId = if (overrideHandler) handler else previous?.handlerId, resourceIds = previous?.resourceIds ?: emptyList(),
                icon = chosenIcon, iconImage = image?.image ?: if (chosenIcon == previous?.icon) previous.iconImage else "",
                selections = previous?.selections ?: emptyMap(),
                chainGroupIds = previous?.chainGroupIds ?: emptyList(), chainRevision = previous?.chainRevision ?: 0,
                fallbackMode = previous?.fallbackMode ?: "none",
                fallbackSelector = previous?.fallbackSelector ?: "", disabledProviders = previous?.disabledProviders ?: emptyList(),
                uploadedBytes = content.usage["upload"] ?: previous?.uploadedBytes,
                downloadedBytes = content.usage["download"] ?: previous?.downloadedBytes,
                totalBytes = content.usage["total"] ?: previous?.totalBytes,
                expiresAt = content.usage["expire"] ?: previous?.expiresAt)
            old.copy(subscriptions = if (previous == null) old.subscriptions + item else old.subscriptions.map { if (it.id == id) item else it },
                selectedId = old.selectedId ?: item.id)
        }

    private fun commit(error: AppError, imported: Boolean = false, onSuccess: () -> Unit = {},
        valid: () -> Boolean = { true }, repairUnlink: String? = null, update: (Library) -> Library): Job {
        return viewModelScope.launch {
            mutations.withLock {
                if (!mutableState.value.loaded) return@withLock
                mutableState.value = mutableState.value.copy(busy = true, error = null, businessIssue = null)
                try {
                    val previous = mutableState.value.library
                    val (library, candidate) = withContext(Dispatchers.IO) {
                        if (!valid()) throw CancellationException()
                        var next = update(previous)
                        if (previous.resources != next.resources) app.engine.validateResources(next.resources)
                        val chainChanges = changedChainGroups(previous.chainLibrary, next.chainLibrary)
                        val resourceChanges = previous.resources != next.resources
                        val compositionChanged = previous.resources != next.resources ||
                            previous.preferences.copy(theme = next.preferences.theme, nodeDensity = next.preferences.nodeDensity,
                                nodeSort = next.preferences.nodeSort, showHiddenGroups = next.preferences.showHiddenGroups,
                                testConcurrency = next.preferences.testConcurrency, connectionReset = next.preferences.connectionReset) != next.preferences
                        ChainCandidateValidator(app).use { validator ->
                        next = next.copy(subscriptions = next.subscriptions.map { subscription ->
                            if (subscription.id == repairUnlink) {
                                require(subscription.chainGroupIds.isEmpty())
                                subscription
                            } else if (compositionChanged || subscription.chainGroupIds.any(chainChanges::contains) ||
                                subscription != previous.subscriptions.firstOrNull { it.id == subscription.id }) {
                                val result = app.engine.project(subscription, next.preferences, next.resources, next.chainLibrary)
                                val oldSubscription = previous.subscriptions.firstOrNull { it.id == subscription.id }
                                if (resourceChanges || oldSubscription?.handlerId != subscription.handlerId ||
                                    oldSubscription?.resourceIds.orEmpty() != subscription.resourceIds ||
                                    (subscription.chainGroupIds.isNotEmpty() && (compositionChanged ||
                                        subscription.chainGroupIds.any(chainChanges::contains) || oldSubscription?.let {
                                            it.original != subscription.original || it.chainGroupIds != subscription.chainGroupIds ||
                                                it.disabledProviders != subscription.disabledProviders ||
                                                it.fallbackMode != subscription.fallbackMode || it.fallbackSelector != subscription.fallbackSelector
                                        } != false))) validator.validate(result.yaml)
                                subscription.copy(ruleProviderCount = result.providers.size,
                                    fallbackMode = if (result.fallbackReset) "none" else subscription.fallbackMode,
                                    fallbackSelector = if (result.fallbackReset) "" else subscription.fallbackSelector)
                            } else subscription
                        })
                        }
                        val candidate = if (repairUnlink == next.selectedId) runCatching { project(next) }.getOrNull() else project(next)
                        next to candidate
                    }
                    val chains = withContext(Dispatchers.IO) { chainState(library.chainLibrary) }
                    val graph = withContext(Dispatchers.IO) { runCatching { resourceGraph(library) }.getOrDefault(ResourceGraph(available = false)) }
                    if (!valid()) throw CancellationException()
                    // Once the atomic write starts, publish its UI state too even
                    // if a URL operation is cancelled at this exact boundary.
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) { app.repository.save(library) }
                        mutableState.value = mutableState.value.copy(library = library, structure = candidate?.structure ?: SubscriptionStructure(),
                            candidate = candidate, projectionFailed = library.selected != null && candidate == null,
                            busy = false, downloadBusy = false, chains = chains, resourceGraph = graph, resourceCheck = null,
                            importRevision = mutableState.value.importRevision + if (imported) 1 else 0,
                            saveRevision = mutableState.value.saveRevision + 1)
                    }
                    onSuccess()
                } catch (cancelled: CancellationException) {
                    mutableState.value = mutableState.value.copy(busy = false, downloadBusy = false)
                    throw cancelled
                } catch (invalid: InvalidRuntimeDraft) {
                    runtimeIssue.value = invalid.issue
                    mutableState.value = mutableState.value.copy(busy = false, downloadBusy = false)
                } catch (failure: Exception) { mutableState.value = mutableState.value.copy(busy = false, downloadBusy = false,
                    businessIssue = businessIssue(failure), error = error.takeUnless { activeDownload?.isCancelled == true })
                } finally { activeDownload = null }
            }
        }
    }
    override fun onCleared() { appUpdater.leaveForeground(); cancelResourceNetwork(); cancelDashboardDownload(); cancelChainNetwork(); diagnostics.cancelDns(); activeDownload?.cancel(); icons.cancel(); tests?.cancel(); singleTests.values.toList().forEach { it.cancel() }; super.onCleared() }

    fun cancelDashboardDownload() {
        dashboardGeneration++; dashboardRequest?.cancel(); dashboardNetwork?.cancel()
        mutableState.value = mutableState.value.copy(dashboardDownloading = false)
    }
    fun setExternalUI(enabled: Boolean) {
        if (!enabled) {
            cancelDashboardDownload()
            savePreferences(mutableState.value.library.preferences.copy(externalUIEnabled = false))
            return
        }
        prepareExternalUI(reinstall = false)
    }
    fun repairExternalUI() = prepareExternalUI(reinstall = true)
    private fun prepareExternalUI(reinstall: Boolean) {
        if (dashboardNetwork?.isActive == true) return
        val generation = ++dashboardGeneration
        val request = Mobile.newResourceDownload()
        val version = mutableState.value.library.preferences.externalUIVersion
        dashboardRequest = request
        mutableState.value = mutableState.value.copy(dashboardDownloading = true, dashboardPrepared = false, error = null, businessIssue = null)
        dashboardNetwork = viewModelScope.launch {
            try {
                val installed = withContext(Dispatchers.IO) { request.dashboard(app.noBackupFilesDir.absolutePath, version, reinstall) }
                ensureActive()
                commit(AppError.DASHBOARD, valid = { generation == dashboardGeneration }) {
                    it.copy(preferences = it.preferences.copy(externalUIEnabled = true, externalUIVersion = installed))
                }.join()
                if (generation == dashboardGeneration && mutableState.value.error == null) {
                    mutableState.value = mutableState.value.copy(dashboardPrepared = true,
                        dashboardRestartSession = app.runtime.live.value?.sessionId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == dashboardGeneration && isActive) mutableState.value = mutableState.value.copy(error = AppError.DASHBOARD)
            }
            finally {
                request.cancel()
                if (generation == dashboardGeneration) {
                    dashboardRequest = null
                    mutableState.value = mutableState.value.copy(dashboardDownloading = false)
                }
            }
        }
    }
    fun openDashboard() {
        try {
            val current = mutableState.value
            check(current.library.preferences.externalUIEnabled)
            val address = app.runtime.dashboardAddress(current.library.selectedId, current.candidate?.revision, current.geoRevision)
            // The short-lived secret stays in the local URL fragment, never app
            // state, persistence, analytics, clipboard or logs.
            app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, address.toUri())
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { mutableState.value = mutableState.value.copy(error = AppError.DASHBOARD) }
    }

    private fun work(error: AppError, onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        viewModelScope.launch {
            mutations.withLock {
                if (!mutableState.value.loaded) return@withLock
                mutableState.value = mutableState.value.copy(busy = true, error = null, businessIssue = null)
                try {
                    withContext(Dispatchers.IO) { action() }
                    mutableState.value = mutableState.value.copy(busy = false)
                    onSuccess()
                } catch (cancelled: CancellationException) {
                    mutableState.value = mutableState.value.copy(busy = false)
                    throw cancelled
                } catch (failure: Exception) {
                    mutableState.value = mutableState.value.copy(error = error, businessIssue = businessIssue(failure), busy = false)
                }
            }
        }
    }
    fun preview() = work(AppError.PREVIEW) {
        val current = mutableState.value.library
        val yaml = app.engine.compose(requireNotNull(current.selected), current.preferences, current.resources, current.chainLibrary)
        withContext(Dispatchers.Main) { viewDocument(yaml) }
    }
    fun checkCore() {
        if (mutableState.value.coreChecking) return
        mutableState.value = mutableState.value.copy(coreChecking = true, coreCheckFailed = false, coreVersion = null)
        viewModelScope.launch {
            try {
                val version = withContext(Dispatchers.IO) { BundledCore(app).version() }
                mutableState.value = mutableState.value.copy(coreVersion = version, coreChecking = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(error = AppError.CORE, coreChecking = false, coreCheckFailed = true) }
        }
    }
    fun updateGeo(kind: String) = work(AppError.CORE) {
        app.geodata.update(kind)
        withContext(Dispatchers.Main) { mutableState.value = mutableState.value.copy(geo = app.geodata.assets()) }
    }
    fun importGeo(kind: String, uri: Uri) = work(AppError.CORE) {
        app.contentResolver.openInputStream(uri)?.use { app.geodata.install(kind, it, "import") } ?: error("cannot_open_document")
        withContext(Dispatchers.Main) { mutableState.value = mutableState.value.copy(geo = app.geodata.assets()) }
    }
    fun restoreGeo() = work(AppError.CORE) {
        require(mutableState.value.library.preferences.geoMode == "mmdb")
        app.geodata.restore()
        withContext(Dispatchers.Main) { mutableState.value = mutableState.value.copy(geo = app.geodata.assets()) }
    }
    suspend fun prepareConnectionRule(input: String, newId: String): Pair<LocalResource, String> = withContext(Dispatchers.IO) {
        val prepared = JSONObject(Mobile.prepareConnectionRule(mutableState.value.library.resources.toJson().toString(), input, newId))
        resourceFromJson(prepared.getJSONObject("resource")) to prepared.getJSONObject("preview").getString("yamlLine")
    }
    fun saveConnectionRule(input: String, newId: String, expected: List<LocalResource>, onSuccess: () -> Unit) =
        commit(AppError.RESOURCE, onSuccess = onSuccess) { old ->
            check(old.resources == expected)
            val prepared = JSONObject(Mobile.prepareConnectionRule(old.resources.toJson().toString(), input, newId))
            val resource = resourceFromJson(prepared.getJSONObject("resource"))
            old.copy(resources = old.resources.replacing(resource))
        }
    fun showConversionReport(report: String?) { mutableState.value = mutableState.value.copy(conversionReport = report) }
    fun dismissChainComposition() { mutableState.value = mutableState.value.copy(chainComposition = null) }
    fun previewChains(id: String) = work(AppError.PREVIEW) {
        val current = mutableState.value.library
        val subscription = current.subscriptions.first { it.id == id }
        val report = app.engine.project(subscription, current.preferences, current.resources, current.chainLibrary).chains
        withContext(Dispatchers.Main) { mutableState.value = mutableState.value.copy(chainComposition = report) }
    }
    fun openChainGroup(group: ChainGroup? = null, kind: String = "manual") {
        chainDraft.value = ChainDraft("group", mutableState.value.chains.revision, groupId = group?.id.orEmpty(),
            name = group?.name.orEmpty(), kind = group?.kind ?: kind,
            selectorFilter = group?.selectorFilter.orEmpty(), nodeFilter = group?.nodeFilter.orEmpty())
    }
    fun openChainItem(group: ChainGroup, id: String = "", source: Boolean = false) = work(AppError.RESOURCE) {
        val current = mutableState.value
        val data = if (id.isEmpty()) "" else Mobile.chainProxyItem(current.library.chainLibrary, group.id, id, if (source) "source" else "node")
        val input = if (source && data.isNotEmpty()) JSONObject(data) else JSONObject()
        val draft = ChainDraft(if (source) "source" else "nodes", current.chains.revision, group.id, id,
            contents = if (source) "" else data, url = input.optString("url"), filter = input.optString("filter"))
        withContext(Dispatchers.Main) { chainDraft.value = draft }
    }
    fun closeChainEditor() { cancelChainNetwork(); chainDraft.value = null }
    private fun chainInput(draft: ChainDraft): JSONObject {
        val input = JSONObject().put("revision", draft.revision).put("groupId", draft.groupId).put("id", draft.id)
        return when (draft.mode) {
            "source" -> input.put("action", "save_source").put("url", draft.url).put("filter", draft.filter)
            "group" -> input.put("action", "save_group").put("name", draft.name).put("kind", draft.kind)
                .put("selectorFilter", draft.selectorFilter).put("nodeFilter", draft.nodeFilter)
            else -> input.put("action", "import_nodes").put("contents", draft.contents)
        }
    }
    fun previewChainEditor() {
        val draft = chainDraft.value ?: return
        val input = chainInput(draft)
        if (draft.mode == "source") { runChainSources(listOf(input), draft, previewOnly = true); return }
        work(AppError.PREVIEW) {
            previewChainMutation(mutableState.value.library, input)
        }
    }
    private suspend fun previewChainMutation(old: Library, input: JSONObject) {
        val result = JSONObject(Mobile.mutateChainLibrary(old.chainLibrary, input.toString()))
        val next = old.copy(chainLibrary = result.getJSONObject("library").toString())
        val id = input.optString("groupId").ifBlank {
            val before = chainState(old.chainLibrary).groups.map { it.id }.toSet()
            chainState(next.chainLibrary).groups.first { it.id !in before }.id
        }
        checkResourceCandidates(next, "chain:$id")
    }
    fun saveChainEditor() {
        val draft = chainDraft.value ?: return
        val input = chainInput(draft)
        if (draft.mode == "source") { runChainSources(listOf(input), draft); return }
        var report: String? = null
        commit(AppError.RESOURCE, onSuccess = {
            if (chainDraft.value == draft) chainDraft.value = null
            if (report != null) showConversionReport(report)
        }) { old ->
            val result = JSONObject(Mobile.mutateChainLibrary(old.chainLibrary, input.toString()))
            report = result.optJSONObject("report")?.toString()
            old.copy(chainLibrary = result.getJSONObject("library").toString())
        }
    }
    fun deleteChainItem(groupId: String, kind: String, id: String, revision: Int, onSuccess: () -> Unit) =
        commit(AppError.RESOURCE, onSuccess = onSuccess) { old ->
            val input = JSONObject().put("action", "delete_$kind").put("groupId", groupId).put("id", id).put("revision", revision)
                .put("usedGroups", JSONArray(old.subscriptions.flatMap { it.chainGroupIds }.distinct()))
            val result = JSONObject(Mobile.mutateChainLibrary(old.chainLibrary, input.toString()))
            old.copy(chainLibrary = result.getJSONObject("library").toString())
        }
    fun associateChains(id: String, groups: List<String>, revision: Int, libraryRevision: Int, onSuccess: () -> Unit) =
        commit(AppError.SAVE, onSuccess = onSuccess, repairUnlink = id.takeIf { groups.isEmpty() }) { old ->
            val subscription = old.subscriptions.first { it.id == id }
            require(subscription.chainRevision == revision) { "chain_proxy:revision_conflict" }
            if (groups.isNotEmpty()) {
                require(chainState(old.chainLibrary).revision == libraryRevision) { "chain_proxy:revision_conflict" }
                require(groups.distinct().size == groups.size && groups.all { group -> mutableState.value.chains.groups.any { it.id == group } }) { "chain_proxy:group_missing" }
            }
            if (subscription.chainGroupIds == groups) old else old.copy(subscriptions = old.subscriptions.map {
                if (it.id == id) it.copy(chainGroupIds = groups.toList(), chainRevision = it.chainRevision + 1) else it
            })
        }
    fun refreshChains(groupId: String = "", sourceId: String = "") {
        if (mutableState.value.chainBusy) return
        val current = mutableState.value
        val requests = current.chains.groups.filter { groupId.isBlank() || it.id == groupId }.flatMap { group ->
            group.sources.filter { sourceId.isBlank() || it.id == sourceId }.map { source ->
                JSONObject().put("action", "save_source").put("groupId", group.id).put("id", source.id)
                    .put("revision", current.chains.revision)
            }
        }
        runChainSources(requests, null)
    }
    fun cancelChainNetwork() {
        chainGeneration++
        chainFetcher?.cancel(); chainNetwork?.cancel()
        mutableState.value = mutableState.value.copy(chainBusy = false)
    }
    private fun runChainSources(requests: List<JSONObject>, editor: ChainDraft?, previewOnly: Boolean = false) {
        if (mutableState.value.chainBusy || requests.isEmpty()) return
        if (requests.first().getInt("revision") != mutableState.value.chains.revision) {
            mutableState.value = mutableState.value.copy(error = AppError.SAVE, businessIssue = BusinessIssue("chain_revision_conflict"))
            return
        }
        val generation = ++chainGeneration
        val snapshot = mutableState.value.library.chainLibrary
        mutableState.value = mutableState.value.copy(chainBusy = true, chainFailures = emptyList(), businessIssue = null, error = null)
        chainNetwork = viewModelScope.launch {
            val deadline = launch { delay(120_000); if (generation == chainGeneration) cancelChainNetwork() }
            var revision = requests.first().getInt("revision")
            try {
                for (originalRequest in requests) {
                    ensureActive()
                    val request = JSONObject(originalRequest.toString()).put("revision", revision)
                    val fetcher = app.subscriptionFetcher()
                    chainFetcher = fetcher
                    try {
                        val body = withContext(Dispatchers.IO) {
                            if (editor == null) {
                                val source = JSONObject(Mobile.chainProxyItem(snapshot, request.getString("groupId"), request.getString("id"), "source"))
                                request.put("url", source.getString("url")).put("filter", source.getString("filter"))
                            }
                            fetcher.fetch(request.getString("url"))
                        }
                        ensureActive()
                        request.put("contents", body)
                        if (previewOnly) {
                            mutations.withLock {
                                withContext(Dispatchers.IO) { previewChainMutation(mutableState.value.library, request) }
                            }
                            continue
                        }
                        var saved = false
                        var report: String? = null
                        commit(AppError.RESOURCE, valid = { generation == chainGeneration }, onSuccess = { saved = true }) { old ->
                            val result = JSONObject(Mobile.mutateChainLibrary(old.chainLibrary, request.toString()))
                            report = result.optJSONObject("report")?.toString()
                            old.copy(chainLibrary = result.getJSONObject("library").toString())
                        }.join()
                        if (saved) {
                            revision = mutableState.value.chains.revision
                            if (editor != null && chainDraft.value == editor) { chainDraft.value = null; showConversionReport(report) }
                        } else {
                            val issue = mutableState.value.businessIssue ?: BusinessIssue("chain_candidate_invalid")
                            recordChainFailure(request, issue)
                            if (editor == null) dismissError()
                            if (issue.code == "chain_revision_conflict") break
                        }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failure: Exception) {
                        if (fetcher.isCancelled) throw CancellationException()
                        val issue = businessIssue(failure) ?: BusinessIssue("chain_fetch_failed")
                        recordChainFailure(request, issue)
                        if (editor != null) mutableState.value = mutableState.value.copy(error = AppError.NETWORK, businessIssue = issue)
                    } finally { if (chainFetcher === fetcher) chainFetcher = null }
                }
            } finally {
                deadline.cancel()
                if (generation == chainGeneration) mutableState.value = mutableState.value.copy(chainBusy = false)
            }
        }
    }
    private fun recordChainFailure(request: JSONObject, issue: BusinessIssue) {
        mutableState.value = mutableState.value.copy(chainFailures = mutableState.value.chainFailures +
            ChainFailure(request.getString("groupId"), request.optString("id"), issue))
    }
    companion object { const val MAX_IMPORT_BYTES = 4 * 1024 * 1024 }
}
private fun List<LocalResource>.replacing(resource: LocalResource) =
    if (any { it.id == resource.id }) map { if (it.id == resource.id) resource else it } else this + resource
