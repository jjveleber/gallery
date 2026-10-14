package com.jjveleber.gallery.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.INTERVALS_SEC
import com.jjveleber.gallery.model.Settings
import com.jjveleber.gallery.model.SortSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

interface SettingsStore {
    val settings: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}

class DataStoreSettingsStore(private val store: DataStore<Preferences>) : SettingsStore {
    override val settings: Flow<Settings> = store.data.map { it.toSettings() }

    override suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs -> transform(prefs.toSettings()).writeTo(prefs) }
    }
}

/** Settings storage that starts over from the defaults if the file is corrupt, instead of failing every read. */
fun settingsDataStore(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    produceFile: () -> File,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = scope,
    produceFile = produceFile,
)

private val SORT_ORDER = stringPreferencesKey("sort_order")
private val SORT_ASCENDING = booleanPreferencesKey("sort_ascending")
private val RANDOM_SEED = longPreferencesKey("random_seed")
private val GRID_SIZE = stringPreferencesKey("grid_size")
private val ANIMATE_GRID = booleanPreferencesKey("animate_grid")
private val ANIMATE_FULL_SCREEN = booleanPreferencesKey("animate_full_screen")
private val INTERVAL_SEC = intPreferencesKey("interval_sec")
private val TRANSITION = stringPreferencesKey("transition")
private val LAST_FOLDER_VOLUME = stringPreferencesKey("last_folder_volume")
private val LAST_FOLDER_PATH = stringPreferencesKey("last_folder_path")

fun Preferences.toSettings(): Settings {
    val d = Settings()
    return Settings(
        sort = SortSpec(
            order = enumOr(this[SORT_ORDER], d.sort.order),
            ascending = this[SORT_ASCENDING] ?: d.sort.ascending,
            randomSeed = this[RANDOM_SEED] ?: d.sort.randomSeed,
        ),
        gridSize = enumOr(this[GRID_SIZE], d.gridSize),
        animateGrid = this[ANIMATE_GRID] ?: d.animateGrid,
        animateFullScreen = this[ANIMATE_FULL_SCREEN] ?: d.animateFullScreen,
        intervalSec = this[INTERVAL_SEC]?.takeIf { it in INTERVALS_SEC } ?: d.intervalSec,
        transition = enumOr(this[TRANSITION], d.transition),
        lastFolder = lastFolder(),
    )
}

private fun Preferences.lastFolder(): FolderRef? {
    val volume = this[LAST_FOLDER_VOLUME] ?: return null
    val path = this[LAST_FOLDER_PATH] ?: return null
    return FolderRef(volume, path)
}

private fun Settings.writeTo(prefs: MutablePreferences) {
    prefs[SORT_ORDER] = sort.order.name
    prefs[SORT_ASCENDING] = sort.ascending
    prefs[RANDOM_SEED] = sort.randomSeed
    prefs[GRID_SIZE] = gridSize.name
    prefs[ANIMATE_GRID] = animateGrid
    prefs[ANIMATE_FULL_SCREEN] = animateFullScreen
    prefs[INTERVAL_SEC] = intervalSec
    prefs[TRANSITION] = transition.name
    if (lastFolder == null) {
        prefs.remove(LAST_FOLDER_VOLUME)
        prefs.remove(LAST_FOLDER_PATH)
    } else {
        prefs[LAST_FOLDER_VOLUME] = lastFolder.volume
        prefs[LAST_FOLDER_PATH] = lastFolder.relativePath
    }
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
