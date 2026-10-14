package com.jjveleber.gallery.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jjveleber.gallery.ui.Screen

@Composable
fun PickerScreen(screen: Screen, onPick: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Gallery", style = MaterialTheme.typography.headlineLarge)
        if (screen is Screen.Scanning) {
            CircularProgressIndicator()
            Text("Found ${"%,d".format(screen.found)} images…")
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        } else {
            Button(onClick = onPick, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp)) {
                Text("Pick folder", style = MaterialTheme.typography.titleLarge)
            }
            Text("Shows every image in the folder you pick, including its subfolders.")
        }
    }
}
