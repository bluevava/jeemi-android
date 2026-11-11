package io.jeemi.android.data

import io.jeemi.android.JeemiApplication
import io.jeemi.android.domain.Library
import kotlinx.coroutines.CancellationException
import mobile.Mobile
import org.json.JSONObject

/** Called under the shared repository mutation lock, before any editor opens.
 * Returns a candidate library only; the caller owns the atomic save. */
internal fun withUpgradedRuntimeDefaults(library: Library, app: JeemiApplication): Library {
    try {
        val raw = library.preferences.runtimeJson.ifBlank {
            // The older path/value representation has no migration marker.
            JSONObject(Mobile.upgradeRuntimePreferences(JSONObject(library.preferences.runtimeValues).toString()))
                .apply { remove("dnsFakeIpFilterDefaultsVersion") }.toString()
        }
        val upgraded = Mobile.upgradeRuntimeDefaults(raw)
        if (upgraded == raw && library.preferences.runtimeJson.isNotBlank()) return library
        // Validate without replacing the migration's preserved fields with a
        // normalized copy. Unknown persisted values and other preferences stay.
        Mobile.normalizeRuntimePreferences(upgraded)
        val next = library.copy(preferences = library.preferences.copy(runtimeJson = upgraded))
        val before = JSONObject(raw).opt("dnsFakeIpFilter")?.toString()
        val after = JSONObject(upgraded).opt("dnsFakeIpFilter")?.toString()
        if (before != after) {
            app.engine.validateResources(next.resources)
            ChainCandidateValidator(app).use { validator ->
                next.subscriptions.forEach { subscription ->
                    val candidate = app.engine.project(subscription, next.preferences, next.resources, next.chainLibrary)
                    require(!candidate.fallbackReset)
                    validator.validate(candidate.yaml)
                }
            }
        }
        return next
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) {
        // A broken existing candidate must remain editable. Defer the upgrade
        // with no saved marker or partial write; never log subscription data.
        android.util.Log.w("Jeemi", "Runtime defaults upgrade deferred")
        return library
    }
}
