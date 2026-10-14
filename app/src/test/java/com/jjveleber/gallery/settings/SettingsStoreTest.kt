package com.jjveleber.gallery.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.GridSize
import com.jjveleber.gallery.model.PRIMARY_VOLUME
import com.jjveleber.gallery.model.Settings
import com.jjveleber.gallery.model.SortOrder
import com.jjveleber.gallery.model.SortSpec
import com.jjveleber.gallery.model.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After fun tearDown() = scope.cancel()

    private fun store() = DataStoreSettingsStore(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "settings.preferences_pb") }),
    )

    @Test fun corruptFileFallsBackToDefaults() = runBlocking {
        val file = File(tmp.root, "corrupt.preferences_pb").apply { writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x04)) }
        val store = DataStoreSettingsStore(settingsDataStore(scope) { file })
        assertEquals(Settings(), store.settings.first())
        store.update { it.copy(autoAdvance = true) }
        assertEquals(true, store.settings.first().autoAdvance)
    }

    @Test fun defaultsWhenEmpty() = runBlocking {
        assertEquals(Settings(), store().settings.first())
    }

    @Test fun roundTripsEveryField() = runBlocking {
        val custom = Settings(
            sort = SortSpec(SortOrder.Random, ascending = false, randomSeed = 99),
            gridSize = GridSize.XL,
            animateGrid = true,
            animateFullScreen = false,
            autoAdvance = true,
            intervalSec = 120,
            transition = Transition.None,
            lastFolder = FolderRef("1a2b-3c4d", "DCIM/Trip_1/"),
        )
        val store = store()
        store.update { custom }
        assertEquals(custom, store.settings.first())
    }

    @Test fun updateTransformsCurrentValue() = runBlocking {
        val store = store()
        store.update { it.copy(intervalSec = 30) }
        store.update { it.copy(autoAdvance = true) }
        assertEquals(Settings(intervalSec = 30, autoAdvance = true), store.settings.first())
    }

    @Test fun unknownEnumNamesFallBackToDefaults() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("sort_order") to "Bogus",
            stringPreferencesKey("grid_size") to "Huge",
            stringPreferencesKey("transition") to "Spin",
        )
        assertEquals(Settings(), prefs.toSettings())
    }

    @Test fun intervalOutsideTheListFallsBackToDefault() {
        assertEquals(5, mutablePreferencesOf(intPreferencesKey("interval_sec") to 7).toSettings().intervalSec)
    }

    @Test fun clearingLastFolderRemovesIt() = runBlocking {
        val store = store()
        store.update { it.copy(lastFolder = FolderRef(PRIMARY_VOLUME, "Pictures/")) }
        store.update { it.copy(lastFolder = null) }
        assertNull(store.settings.first().lastFolder)
    }

    @Test fun lastFolderNeedsBothKeys() {
        assertNull(mutablePreferencesOf(stringPreferencesKey("last_folder_volume") to PRIMARY_VOLUME).toSettings().lastFolder)
        assertNull(mutablePreferencesOf(stringPreferencesKey("last_folder_path") to "Pictures/").toSettings().lastFolder)
    }
}
