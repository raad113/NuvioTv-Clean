package com.nuvio.tv.core.cleanstream

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NuvioTv-Family built-in setup.
 *
 * On first start (once per device, and once per profile for the collection) it:
 *  - adds the CleanStream add-on, so the CleanStream rows with tagged posters
 *    appear without the viewer installing anything;
 *  - imports the ready-made family collection from the CleanStream server.
 *
 * Each step runs only once: if the viewer later removes the add-on or a
 * collection, it is not added back. Failures (for example no internet on
 * first start) are retried on the next app start until the step succeeds.
 */
@Singleton
class CleanStreamFirstRun @Inject constructor(
    @ApplicationContext private val context: Context,
    private val addonRepository: AddonRepository,
    private val collectionsDataStore: CollectionsDataStore,
    private val profileManager: ProfileManager,
) {
    companion object {
        /** The CleanStream add-on's manifest id. */
        const val ADDON_ID = "community.cleanstream"
        private const val TAG = "CleanStreamFirstRun"
        private const val PREFS = "clean_stream_first_run"
        private const val KEY_ADDON_DONE = "addon_added_v1"
        private const val KEY_COLLECTION_DONE_PREFIX = "collection_imported_v1_"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun start() {
        scope.launch {
            runCatching { addAddonOnce() }.onFailure { Log.w(TAG, "Add-on setup failed: ${it.message}") }
        }
        // Collections belong to a profile: import once for each profile when it becomes active
        scope.launch {
            profileManager.activeProfileId.collect { profileId ->
                // Subtitle muting is a per-profile choice
                CleanStreamSettings.setActiveProfile(context, profileId)
                runCatching { importCollectionOnce(profileId) }
                    .onFailure { Log.w(TAG, "Collection import failed: ${it.message}") }
            }
        }
    }

    private fun serverUrl(): String = CleanStreamSettings.config(context).value.serverUrl.trimEnd('/')

    private suspend fun addAddonOnce() {
        if (prefs.getBoolean(KEY_ADDON_DONE, false)) return
        val installed = addonRepository.getInstalledAddons().first()
        if (installed.none { it.id == ADDON_ID }) {
            addonRepository.addAddon(serverUrl())
            Log.i(TAG, "CleanStream add-on added")
        }
        prefs.edit().putBoolean(KEY_ADDON_DONE, true).apply()
    }

    private suspend fun importCollectionOnce(profileId: Int) {
        val key = KEY_COLLECTION_DONE_PREFIX + profileId
        if (prefs.getBoolean(key, false)) return

        val json = download("${serverUrl()}/collection.json") ?: return // retry on next start
        val imported = collectionsDataStore.importFromJson(json)
        if (imported.isEmpty()) return

        val current = collectionsDataStore.getCurrentCollections()
        val existingIds = current.map { it.id }.toSet()
        val added = imported.filter { it.id !in existingIds }
        if (added.isNotEmpty()) {
            collectionsDataStore.setCollections(current + added)
            Log.i(TAG, "Imported ${added.size} CleanStream collections")
        }
        prefs.edit().putBoolean(key, true).apply()
    }

    private fun download(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "NuvioTv-Family")
            if (connection.responseCode != 200) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for $url: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
