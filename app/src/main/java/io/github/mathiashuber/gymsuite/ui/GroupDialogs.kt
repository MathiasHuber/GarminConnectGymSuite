package io.github.mathiashuber.gymsuite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mathiashuber.gymsuite.data.ExerciseGroup
import io.github.mathiashuber.gymsuite.data.exerciseDisplayName

/** Name a new group; [members] lists the Garmin exercises it will contain. */
@Composable
fun GroupNameDialog(
    title: String,
    initial: String,
    members: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Combines:", style = MaterialTheme.typography.labelMedium)
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    members.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Rename a group, remove members, or dissolve it. */
@Composable
fun EditGroupDialog(
    group: ExerciseGroup,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onUngroup: () -> Unit,
) {
    var name by remember(group.id) { mutableStateOf(group.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Exercises in this group", style = MaterialTheme.typography.labelMedium)
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    group.members.forEach { key ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(exerciseDisplayName(key), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { onRemoveMember(key) }) { Icon(Icons.Default.Close, "Remove from group") }
                        }
                    }
                }
                TextButton(onClick = onUngroup) { Text("Ungroup all") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(name); onDismiss() }, enabled = name.isNotBlank()) { Text("Done") }
        },
    )
}

/** Put a single exercise into an existing group or a new one. */
@Composable
fun AddToGroupDialog(
    exerciseName: String,
    groups: List<ExerciseGroup>,
    onDismiss: () -> Unit,
    onAddTo: (ExerciseGroup) -> Unit,
    onNewGroup: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add \"$exerciseName\" to a group") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                groups.sortedBy { it.name.lowercase() }.forEach { g ->
                    Text(
                        g.name + "  (${g.members.size})",
                        Modifier.fillMaxWidth().clickable { onAddTo(g) }.padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                }
                Text(
                    "+ New group…",
                    Modifier.fillMaxWidth().clickable(onClick = onNewGroup).padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
