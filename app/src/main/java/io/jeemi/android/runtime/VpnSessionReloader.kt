package io.jeemi.android.runtime

import android.os.Build
import android.util.AtomicFile
import io.jeemi.android.JeemiApplication
import io.jeemi.android.data.validateCoreFile
import io.jeemi.android.domain.RuntimeState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.withLock
import mobile.Mobile
import mobile.ProviderCacheSession
import org.json.JSONObject
import java.io.File

/** One collector per live service session. Repository revisions coalesce while
 * a transaction runs; in-flight core applications are never replaced in parallel. */
internal class VpnSessionReloader(
    private val app: JeemiApplication,
    private val owner: JeemiVpnService,
    private val token: VpnRequest,
    private val client: CoreApi,
    private val home: File,
    private val cache: ProviderCacheSession,
    private val port: Int,
    private val secret: String,
    private val geoRevision: String,
    private val healthy: () -> Boolean,
) {
    private val controller get() = app.runtime
    private var applied = token
    private var configuration = File(home, "config.yaml").readText()
    private var diagnostics = requireNotNull(controller.dnsSession)
    private fun owned() = controller.service === owner && controller.request === token && controller.api === client

    suspend fun observe(initialRevision: Long) {
        var observed = initialRevision
        app.repository.revision.collect { revision ->
            if (revision == observed) return@collect
            observed = revision
            if (!owned()) return@collect
            try {
                val prepared = app.repository.mutations.withLock {
                    val library = app.repository.load()
                    val selected = library.selected ?: return@withLock null
                    val preferences = app.engine.normalizePreferences(library.preferences)
                    val candidate = app.engine.project(selected, preferences, library.resources, library.chainLibrary)
                    require(!candidate.fallbackReset)
                    val runtime = JSONObject(preferences.runtimeJson)
                    app.repository.revision.value to VpnRequest(selected.id, candidate.yaml, runtime.getBoolean("ipv6"),
                        candidate.revision, runtime.getString("logLevel"), selected.selections,
                        candidate.structure.groups.associate { it.name to it.defaultSelected },
                        preferences.externalUIVersion.takeIf { preferences.externalUIEnabled }.orEmpty(), preferences.tunStack,
                        app.repository.revision.value)
                } ?: return@collect
                apply(prepared.second, prepared.first)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (stopped: TunnelDeactivated) { throw stopped
            } catch (fatal: ReloadRecoveryFailed) { throw fatal
            } catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) { if (owned()) controller.updateFailed.value = true }
            }
        }
    }

    private fun tunnelKey(request: VpnRequest): String = request.tunStack.configValue + ":" + request.ipv6 + ":" +
        Mobile.androidVpnRoutes(request.yaml, request.ipv6, Build.VERSION.SDK_INT < 33)

    private suspend fun apply(request: VpnRequest, repositoryRevision: Long) {
        if (request.profileId == applied.profileId && request.revision == applied.revision) {
            withContext(Dispatchers.Main.immediate) { if (owned()) controller.updateFailed.value = false }
            return
        }
        val currentGeo = app.geodata.assets().joinToString(":") { it.sha256 }
        // The core's PUT endpoint also leaves external UI/controller setup to
        // startup, so an enabled/version change needs a new managed session.
        val reconnect = tunnelKey(request) != tunnelKey(applied) || currentGeo != geoRevision ||
            request.externalUIVersion != applied.externalUIVersion
        // Preserve an actual manual selection (including dashboard changes),
        // then validate it against the new members after the core has loaded them.
        val previous = applied.copy(selections = applied.selections + controller.live.value?.proxies.orEmpty()
            .filterValues { it.type == "Selector" }.mapValues { it.value.now })
        val next = if (request.profileId == applied.profileId) request.copy(selections = request.selections + previous.selections) else request
        if (reconnect) {
            io.jeemi.android.data.ChainCandidateValidator(app).use { it.validate(next.yaml) }
            currentCoroutineContext().ensureActive()
            requireTunnel()
            withContext(Dispatchers.Main.immediate) {
                if (owned() && controller.state.value is RuntimeState.Running && app.repository.revision.value == repositoryRevision)
                    controller.reconfigure(next, owner, token)
            }
            return
        }
        val choices = JSONObject(next.defaults + next.selections).toString()
        val primed = Mobile.primeStartupSelections(next.yaml, choices)
        if (next.externalUIVersion.isNotEmpty()) Mobile.materializeDashboard(app.noBackupFilesDir.absolutePath, next.externalUIVersion, home.absolutePath)
        val androidConfig = Mobile.androidSessionConfiguration(primed, secret, port.toLong(), next.ipv6, next.tunStack.configValue)
        val dashboard = Mobile.androidDashboardConfiguration(androidConfig, next.externalUIVersion)
        val nextDiagnostics = JSONObject(Mobile.reloadDNSDiagnostics(dashboard, diagnostics.toString()))
        val generations = File(home, "generations").apply { mkdirs() }
        val staged = File(generations, "reload.yaml")
        staged.writeText(nextDiagnostics.getString("configuration"))
        cache.prepareReload()
        validateCoreFile(app, home, staged)
        currentCoroutineContext().ensureActive()
        while (!controller.configurationChanges.tryAcquire()) delay(25)
        try {
        requireTunnel()
        val begin = withContext(Dispatchers.Main.immediate) {
            if (!owned() || controller.state.value !is RuntimeState.Running || app.repository.revision.value != repositoryRevision) false
            else { controller.updateFailed.value = false; controller.state.value = RuntimeState.Updating; true }
        }
        if (!begin) return
        try {
            client.reload(staged.absolutePath)
            currentCoroutineContext().ensureActive()
            controller.restoreSelections(client, next)
            client.call("/configs", "PATCH", JSONObject().put("log-level", next.logLevel))
            val contents = staged.readText()
            verify(contents)
            persist(contents)
            withContext(Dispatchers.Main.immediate) {
                if (!owned()) throw CancellationException()
                applied = next
                configuration = contents
                diagnostics = nextDiagnostics.getJSONObject("session")
                controller.appliedRequest = next
                controller.dnsSession = diagnostics
                // A fresh logical identity invalidates old observations/actions,
                // although the process, VPN descriptor and controller stay alive.
                controller.live.value = LiveSession(next.profileId, next.revision, geoRevision)
                controller.state.value = RuntimeState.Running(next.revision)
            }
            runCatching { controller.refresh(client) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (stopped: TunnelDeactivated) { throw stopped
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (!owned()) throw CancellationException()
            try {
                val rollback = File(generations, "rollback.yaml").apply { writeText(configuration) }
                // Even a timed-out PUT might still execute. The core serializes
                // applications, so an acknowledged rollback restores known state.
                client.reload(rollback.absolutePath)
                controller.restoreSelections(client, previous)
                client.call("/configs", "PATCH", JSONObject().put("log-level", previous.logLevel))
                verify(configuration)
                persist(configuration)
                withContext(Dispatchers.Main.immediate) {
                    if (!owned()) throw CancellationException()
                    controller.live.value = LiveSession(previous.profileId, previous.revision, geoRevision)
                    controller.state.value = RuntimeState.Running(previous.revision)
                    controller.updateFailed.value = true
                }
                runCatching { controller.refresh(client) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (stopped: TunnelDeactivated) { throw stopped
            } catch (_: Exception) { throw ReloadRecoveryFailed() }
        } finally {
            staged.delete()
            File(generations, "rollback.yaml").delete()
        }
        } finally { controller.configurationChanges.release() }
    }

    private fun persist(contents: String) {
        val file = AtomicFile(File(home, "config.yaml"))
        val stream = file.startWrite()
        try { stream.write(contents.toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun verify(contents: String) {
        val status = requireTunnel()
        Mobile.validateReloadedConfiguration(contents, status.toString())
    }
    private fun requireTunnel(): JSONObject {
        if (!healthy()) throw TunnelDeactivated()
        val status = client.call("/configs")
        if (!coreTunnelEnabled(status)) throw TunnelDeactivated()
        return status
    }
}

internal class ReloadRecoveryFailed : Exception()
