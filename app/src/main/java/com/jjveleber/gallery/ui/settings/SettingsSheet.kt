package com.jjveleber.gallery.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jjveleber.gallery.model.INTERVALS_SEC
import com.jjveleber.gallery.model.Settings
import com.jjveleber.gallery.model.Transition
import com.jjveleber.gallery.slideshow.intervalLabel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Grid", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Animate thumbnails", settings.animateGrid) { v -> onUpdate { it.copy(animateGrid = v) } }
            Text("Full screen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            SwitchRow("Animate images", settings.animateFullScreen) { v -> onUpdate { it.copy(animateFullScreen = v) } }
            SwitchRow("Auto advance", settings.autoAdvance) { v -> onUpdate { it.copy(autoAdvance = v) } }
            Text("Interval", modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERVALS_SEC.forEach { sec ->
                    FilterChip(
                        selected = settings.intervalSec == sec,
                        onClick = { onUpdate { it.copy(intervalSec = sec) } },
                        label = { Text(intervalLabel(sec)) },
                    )
                }
            }
            Text("Transition", modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Transition.entries.forEach { t ->
                    FilterChip(
                        selected = settings.transition == t,
                        onClick = { onUpdate { it.copy(transition = t) } },
                        label = { Text(t.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
