package com.aethernova.nova.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GroceryScreen(vm: NovaViewModel, onBack: () -> Unit) {
    val items by vm.groceries.collectAsState()
    val busy by vm.groceryBusy.collectAsState()
    var newItem by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
            Text("Grocery list", color = C.text, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Text("${items.count { !it.done }} left", color = C.muted, fontSize = 13.sp)
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.generateGroceries() }, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text(if (busy) "Planning…" else "Plan this week") }
            OutlinedButton(onClick = { vm.clearDoneGroceries() }) { Text("Clear ticked") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newItem, { newItem = it }, Modifier.weight(1f), placeholder = { Text("Add item") }, singleLine = true)
            TextButton(onClick = { vm.addGrocery(newItem); newItem = "" }) { Text("Add") }
        }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            items(items, key = { it.id }) { g ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(g.done, { vm.toggleGrocery(g) })
                    Column(Modifier.weight(1f)) {
                        Text(g.name, color = if (g.done) C.muted else C.text,
                            textDecoration = if (g.done) TextDecoration.LineThrough else null)
                        if (g.qty.isNotBlank()) Text(g.qty, color = C.muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { vm.deleteGrocery(g) }) { Icon(Icons.Default.Delete, "Delete", tint = C.muted) }
                }
            }
            if (items.isEmpty()) item {
                Text("Tap \"Plan this week\" and Nova builds a list from your plan and what you've been eating. Tick items off in the shop.",
                    color = C.muted, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun TextButton(onClick: () -> Unit, content: @Composable () -> Unit) =
    androidx.compose.material3.TextButton(onClick = onClick) { content() }
