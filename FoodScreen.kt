package com.aethernova.nova.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.aethernova.nova.data.FoodEntry
import com.aethernova.nova.data.LocalFood
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun FoodScreen(vm: NovaViewModel) {
    val day by vm.foodDay.collectAsState()
    val food by vm.foodForDay.collectAsState()
    val s by vm.settings.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scanned by vm.scannedProduct.collectAsState()
    val ideas by vm.fridgeIdeas.collectAsState()
    val thinking by vm.thinking.collectAsState()
    var fridgeUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val fridgeCam = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        if (ok) fridgeUri?.let { u -> loadBitmap(ctx, u)?.let { vm.photo(it, PhotoMode.FRIDGE, "") } }
    }
    val date = LocalDate.parse(day)
    val isToday = date == LocalDate.now()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.foodDay.value = date.minusDays(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", tint = C.text) }
            Text(if (isToday) "Today" else date.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                Modifier.weight(1f), color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { if (!isToday) vm.foodDay.value = date.plusDays(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", tint = if (isToday) C.surface2 else C.text) }
        }

        if (isToday) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { vm.scanBarcode(ctx) }, label = { Text("📷 Scan barcode") })
            AssistChip(onClick = {
                val dir = java.io.File(ctx.cacheDir, "photos").apply { mkdirs() }
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", java.io.File(dir, "fridge.jpg"))
                fridgeUri = uri; fridgeCam.launch(uri)
            }, label = { Text("🧊 Fridge ideas") })
            AssistChip(onClick = { vm.open("grocery") }, label = { Text("🛒 Grocery list") })
            AssistChip(onClick = { vm.open("cook") }, label = { Text("🍳 Cook with me") })
            AssistChip(onClick = { vm.open("mealprep") }, label = { Text("🥡 2-day prep") })
        }
        if (thinking) LinearProgressIndicator(Modifier.fillMaxWidth(), color = C.teal, trackColor = C.surface2)

        val kcal = food.sumOf { it.kcal }
        val p = food.sumOf { it.proteinG }
        val carbs = food.sumOf { it.carbsG }
        val fat = food.sumOf { it.fatG }
        NovaCard {
            Bar("Protein", p, s.proteinTarget.toDouble(), "g", C.teal)
            Bar("Calories", kcal.toDouble(), s.kcalTarget.toDouble(), "kcal", C.amber)
            Text("Carbs ${"%.0f".format(carbs)} g · Fat ${"%.0f".format(fat)} g", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            val mix = com.aethernova.nova.data.LiverScore.mix(food)
            if (mix.total > 0) Text("Liver-friendly: 🟢 ${mix.green} · 🟠 ${mix.amber} · 🔴 ${mix.red}  (${mix.greenPct}% green)",
                color = if (mix.red > mix.green) C.amber else C.muted, fontSize = 13.sp)
        }

        if (isToday) {
            Text("Quick add", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalFood.quickAdd.forEach { item ->
                    AssistChip(onClick = { vm.addFood(LocalFood.toEntry(item, 1.0, "quick", day, System.currentTimeMillis())) },
                        label = { Text("${item.name.substringBefore(" (")} +${item.protein.toInt()}g") })
                }
            }
        }

        LazyColumn(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(food, key = { it.id }) { f ->
                NovaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val lv = f.liver.ifBlank { com.aethernova.nova.data.LiverScore.local(f.name) }
                            Text((when (lv) { "green" -> "🟢 "; "red" -> "🔴 "; else -> "🟠 " }) + f.name, color = C.text, fontWeight = FontWeight.Medium)
                            Text("${f.portion} · ${f.kcal} kcal · ${"%.0f".format(f.proteinG)} g protein · ${f.source}", color = C.muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { vm.deleteFoodAndRefresh(f) }) { Icon(Icons.Default.Delete, "Delete", tint = C.muted) }
                    }
                }
            }
            if (food.isEmpty()) item { Text("Nothing logged. Snap a photo on the Nova tab or tell Nova what you ate.", color = C.muted) }
        }

        if (isToday) ExtendedFloatingActionButton(
            onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add manually") },
            containerColor = C.teal, contentColor = C.bg, modifier = Modifier.align(Alignment.End),
        )
    }

    scanned?.let { p ->
        var grams by remember(p) { mutableStateOf((p.servingG ?: 100.0).toInt().toString()) }
        AlertDialog(
            onDismissRequest = { vm.scannedProduct.value = null },
            title = { Text(listOf(p.brand, p.name).filter { it.isNotBlank() }.joinToString(" ")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Per 100 g: ${p.kcal100.toInt()} kcal · ${"%.1f".format(p.protein100)} g protein · ${"%.1f".format(p.carbs100)} g carbs · ${"%.1f".format(p.fat100)} g fat", color = C.muted, fontSize = 13.sp)
                    p.sugar100?.let { Text("Sugar ${"%.1f".format(it)} g /100 g" + if (p.highSugar) " — HIGH (breaks your no-sugar rule)" else "", color = if (p.highSugar) C.red else C.muted, fontSize = 13.sp) }
                    OutlinedTextField(grams, { grams = it }, label = { Text("How many grams did you have?") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    grams.toDoubleOrNull()?.let { g -> Text("= ${(p.kcal100 * g / 100).toInt()} kcal · ${"%.0f".format(p.protein100 * g / 100)} g protein", color = C.teal) }
                    Text("Data: Open Food Facts", color = C.muted, fontSize = 10.sp)
                }
            },
            confirmButton = { TextButton(enabled = (grams.toDoubleOrNull() ?: 0.0) > 0, onClick = { vm.logScanned(grams.toDouble()) }) { Text("Log it") } },
            dismissButton = { TextButton(onClick = { vm.scannedProduct.value = null }) { Text("Cancel") } },
        )
    }
    ideas?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.fridgeIdeas.value = null },
            title = { Text("What you can make") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { vm.fridgeIdeas.value = null }) { Text("Thanks") } },
        )
    }

    if (adding) ManualFoodDialog(day, onAdd = { vm.addFood(it); adding = false }, onCancel = { adding = false })
}

@Composable
fun Bar(label: String, v: Double, target: Double, unit: String, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(label, color = C.text, modifier = Modifier.weight(1f))
            Text("${"%.0f".format(v)} / ${"%.0f".format(target)} $unit", color = if (v > target * 1.1 && unit == "kcal") C.red else C.muted, fontSize = 13.sp)
        }
        LinearProgressIndicator(progress = { (v / target).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 4.dp), color = color, trackColor = C.surface2)
    }
}

@Composable
fun ManualFoodDialog(day: String, onAdd: (FoodEntry) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var portion by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add food") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(portion, { portion = it }, label = { Text("Portion") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(kcal, { kcal = it }, Modifier.weight(1f), label = { Text("kcal") }, keyboardOptions = num, singleLine = true)
                    OutlinedTextField(protein, { protein = it }, Modifier.weight(1f), label = { Text("Protein g") }, keyboardOptions = num, singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(carbs, { carbs = it }, Modifier.weight(1f), label = { Text("Carbs g") }, keyboardOptions = num, singleLine = true)
                    OutlinedTextField(fat, { fat = it }, Modifier.weight(1f), label = { Text("Fat g") }, keyboardOptions = num, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onAdd(FoodEntry(
                    liver = com.aethernova.nova.data.LiverScore.local(name),
                    timestamp = System.currentTimeMillis(), day = day, name = name.trim(), portion = portion.trim(),
                    kcal = kcal.toDoubleOrNull()?.toInt() ?: 0, proteinG = protein.toDoubleOrNull() ?: 0.0,
                    carbsG = carbs.toDoubleOrNull() ?: 0.0, fatG = fat.toDoubleOrNull() ?: 0.0, source = "manual",
                ))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
