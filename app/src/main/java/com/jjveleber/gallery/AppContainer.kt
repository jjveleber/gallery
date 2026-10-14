package com.jjveleber.gallery

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.jjveleber.gallery.imaging.AnimatedRegistry
import com.jjveleber.gallery.scan.AndroidFolderScanner
import com.jjveleber.gallery.scan.AndroidOpenWithResolver
import com.jjveleber.gallery.scan.FolderScanner
import com.jjveleber.gallery.scan.OpenWithResolver
import com.jjveleber.gallery.settings.DataStoreSettingsStore
import com.jjveleber.gallery.settings.SettingsStore
import com.jjveleber.gallery.settings.settingsDataStore

class AppContainer(context: Context) {
    val animated = AnimatedRegistry()
    val settings: SettingsStore = DataStoreSettingsStore(
        settingsDataStore(produceFile = { context.preferencesDataStoreFile("settings") }),
    )
    val scanner: FolderScanner = AndroidFolderScanner(context.contentResolver)
    val openWith: OpenWithResolver = AndroidOpenWithResolver(context)
}
