package com.aethernova.nova.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethernova.nova.data.HealthResult
import java.time.LocalDate

/** Common markers with typical UK adult reference ranges (your lab's printed range wins). */
private data class Preset(val marker: String, val unit: String, val low: Double?, val high: Double?)
private val presets = listOf(
    Preset("Vitamin D (25-OH)", "ng/mL", 30.0, 100.0),
    Preset("ALT", "U/L", null, 40.0),
    Preset("AST", "U/L", null, 40.0),
    Preset("GGT", "U/L", null, 55.0),
    Preset("HbA1c", "mmol/mol", null, 42.0),
    Preset("Fasting glucose", "mmol/L", 3.9, 5.5),
    Preset("Total cholesterol", "mmol/L", null, 5.0),
    Preset("LDL", "mmol/L", null, 3.0),
    Preset("HDL", "mmol/L", 1.0, null),
    Preset("Triglycerides", "mmol/L", null, 1.7),
    Preset("Vitamin B12", "ng/L", 197.0, 771.0),
    Preset("Ferritin", "µg/L", 30.0, 400.0),
    Preset("TSH", "mU/L", 0.27, 4.2),
    Preset("Liver ultrasound", "", null, null),
)

@Composable
fun HealthScreen(vm: NovaViewModel) {
    val results by vm.results.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val grouped = results.groupBy { it.marker }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Health", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Stored encrypted on this phone. Only the numbers are shared with the AI (toggle in Settings). Nova explains; your doctor or pharmacist decides.",
            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { HealthExtras(vm) }
            item { Text("Test results", color = C.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)) }
            items(grouped.keys.toList()) { marker ->
                val list = grouped[marker].orEmpty().sortedBy { it.day }
                val latest = list.last()
                NovaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(marker, color = C.text, fontWeight = FontWeight.SemiBold)
                            Text(
                                (latest.value?.let { "$it ${latest.unit}" } ?: "") + "  ${latest.day}",
                                color = C.muted, fontSize = 13.sp,
                            )
                        }
                        val flagColor = when (latest.flag) { "LOW", "HIGH" -> C.amber; "OK" -> C.green; else -> C.muted }
                        if (latest.flag.isNotEmpty()) Text(latest.flag, color = flagColor, fontWeight = FontWeight.Bold)
                    }
                    val range = listOfNotNull(latest.refLow?.let { "≥$it" }, latest.refHigh?.let { "≤$it" }).joinToString(" ")
                    if (range.isNotEmpty()) Text("Reference $range ${latest.unit}", color = C.muted, fontSize = 12.sp)
                    if (latest.note.isNotBlank()) Text(latest.note, color = C.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    val vals = list.mapNotNull { it.value }
                    if (vals.size >= 2) LineChart(vals, if (latest.flag == "OK") C.green else C.amber, Modifier.padding(top = 8.dp), height = 70.dp)
                    list.reversed().forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.day}  ${r.value ?: "—"} ${r.unit}", color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.deleteResult(r) }) { Icon(Icons.Default.Delete, "Delete", tint = C.muted) }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add result") },
            containerColor = C.teal, contentColor = C.bg, modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
        )
    }
    if (adding) AddResultDialog(onAdd = { vm.addResult(it); adding = false }, onCancel = { adding = false })
}

@Composable
private fun AddResultDialog(onAdd: (HealthResult) -> Unit, onCancel: () -> Unit) {
    var marker by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var low by remember { mutableStateOf("") }
    var high by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(LocalDate.now().toString()) }
    var note by remember { mutableStateOf("") }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add test result") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { p ->
                        AssistChip(onClick = {
                            marker = p.marker; unit = p.unit
                            low = p.low?.toString() ?: ""; high = p.high?.toString() ?: ""
                        }, label = { Text(p.marker) })
                    }
                }
                OutlinedTextField(marker, { marker = it }, label = { Text("Marker") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value, { value = it }, Modifier.weight(1f), label = { Text("Value") }, keyboardOptions = num, singleLine = true)
                    OutlinedTextField(unit, { unit = it }, Modifier.weight(1f), label = { Text("Unit") }, singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(low, { low = it }, Modifier.weight(1f), label = { Text("Ref low") }, keyboardOptions = num, singleLine = true)
                    OutlinedTextField(high, { high = it }, Modifier.weight(1f), label = { Text("Ref high") }, keyboardOptions = num, singleLine = true)
                }
                Text("Use the range printed on your report if it's different.", color = C.muted, fontSize = 11.sp)
                OutlinedTextField(day, { day = it }, label = { Text("Date (yyyy-mm-dd)") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Note (e.g. scan finding)") })
            }
        },
        confirmButton = {
            TextButton(enabled = marker.isNotBlank() && runCatching { LocalDate.parse(day) }.isSuccess, onClick = {
                onAdd(HealthResult(day = day, marker = marker.trim(), value = value.toDoubleOrNull(), unit = unit.trim(),
                    refLow = low.toDoubleOrNull(), refHigh = high.toDoubleOrNull(), note = note.trim()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
