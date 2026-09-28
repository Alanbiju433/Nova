package com.aethernova.nova.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethernova.nova.data.Plan
import java.time.LocalDate

@Composable
fun BodyScreen(vm: NovaViewModel, onConnectHealth: () -> Unit) {
    val s by vm.settings.collectAsState()
    val weights by vm.weights.collectAsState()
    val workouts by vm.weekWorkouts.collectAsState()
    val todayCheck by vm.todayCheckIn.collectAsState()
    val vitals by vm.vitals.collectAsState()
    val hcConnected by vm.healthConnected.collectAsState()
    val today = LocalDate.now()
    val week = Plan.weekNumber(s.planStart, today)
    val session = Plan.sessionFor(today)

    var sleep by remember { mutableFloatStateOf((vitals.sleepLastNightH ?: 7.0).toFloat()) }
    var mood by remember { mutableStateOf<Int?>(null) }
    var energy by remember { mutableStateOf<Int?>(null) }
    var weight by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var walkMin by remember { mutableStateOf("40") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        NovaCard {
            SectionTitle("Morning check-in")
            if (todayCheck?.mood != null) Text("Done today ✓ (mood ${todayCheck?.mood}/5, sleep ${todayCheck?.sleepHours ?: "?"} h)", color = C.green, fontSize = 13.sp)
            Text("Sleep: ${"%.1f".format(sleep)} h" + (vitals.sleepLastNightH?.let { " (watch says ${"%.1f".format(it)})" } ?: ""), color = C.text)
            Slider(sleep, { sleep = (it * 2).toInt() / 2f }, valueRange = 3f..10f)
            Text("Mood", color = C.muted, fontSize = 13.sp)
            ScaleRow(mood, listOf("😣", "😕", "😐", "🙂", "😄")) { mood = it }
            Text("Energy", color = C.muted, fontSize = 13.sp)
            ScaleRow(energy, listOf("1", "2", "3", "4", "5")) { energy = it }
            OutlinedTextField(weight, { weight = it }, label = { Text("Weight kg (optional — weekly is enough)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Journal — how's your head today? (optional)") },
                modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Button(onClick = {
                vm.saveCheckIn(sleep.toDouble(), mood, energy, weight.toDoubleOrNull(), note.trim()); weight = ""; note = ""
            }, Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Save check-in") }
        }

        NovaCard {
            SectionTitle(session?.title ?: "Rest day — walk only")
            Text(Plan.dosage(week), color = C.amber, fontSize = 13.sp)
            session?.exercises?.forEach { e ->
                Column(Modifier.padding(top = 8.dp)) {
                    Text("• ${e.name}", color = C.text)
                    Text(e.cue, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(walkMin, { walkMin = it }, Modifier.weight(1f), label = { Text("Walk min") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedButton(onClick = { vm.logWorkout("walk", walkMin.toIntOrNull() ?: 30) }) { Text("Walk done") }
                if (session != null) Button(onClick = { vm.logWorkout(session.code, 35) },
                    colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Session ${session.code} done") }
            }
        }

        NovaCard {
            SectionTitle("Camera coach")
            Text("Prop your phone on the floor side-on. Nova counts your reps out loud and flags form slips.", color = C.muted, fontSize = 13.sp)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.open("coach:SQUAT") }, colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Squats") }
                Button(onClick = { vm.open("coach:PUSHUP") }, colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Push-ups") }
            }
        }

        GymCard(vm)

        ProgressPhotos(vm)

        NovaCard {
            SectionTitle("This week")
            val sessionsDone = workouts.count { it.kind in listOf("A", "B", "C") }
            val walkMins = workouts.filter { it.kind == "walk" }.sumOf { it.minutes }
            Text("$sessionsDone / 3 sessions · $walkMins min walking", color = C.text)
            workouts.take(10).forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${w.day.substring(5)}  ${if (w.kind == "walk") "Walk" else "Session ${w.kind}"} · ${w.minutes} min", color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.deleteWorkout(w) }) { Text("undo", fontSize = 12.sp) }
                }
            }
        }

        NovaCard {
            SectionTitle("Weight trend")
            val pts = weights.mapNotNull { it.weightKg }
            LineChart(pts.takeLast(30))
            if (pts.isNotEmpty()) {
                val change = pts.last() - pts.first()
                Text("Now ${pts.last()} kg · ${if (change <= 0) "" else "+"}${"%.1f".format(change)} kg since start", color = C.text, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            }
        }

        NovaCard {
            SectionTitle("Steps & sleep from your phone/watch")
            if (!vm.health.available) Text("Health Connect isn't installed. Install it from the Play Store to share steps and sleep.", color = C.muted, fontSize = 13.sp)
            else if (!hcConnected) Button(onClick = onConnectHealth) { Text("Connect Health Connect") }
            else Text("Connected ✓  Steps today: ${vitals.stepsToday ?: 0}" + (vitals.sleepLastNightH?.let { " · Sleep ${"%.1f".format(it)} h" } ?: ""), color = C.green)
        }
    }
}

@Composable
private fun ScaleRow(selected: Int?, labels: List<String>, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l ->
            FilterChip(selected = selected == i + 1, onClick = { onPick(i + 1) }, label = { Text(l) })
        }
    }
}

@Composable
private fun ProgressPhotos(vm: NovaViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val photos by vm.photos.collectAsState()
    var pending by remember { mutableStateOf<java.io.File?>(null) }
    var compare by remember { mutableStateOf(false) }
    val cam = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        pending?.let { f -> if (ok && f.exists()) vm.addProgressPhoto(f.absolutePath) else f.delete() }
        pending = null
    }
    NovaCard {
        SectionTitle("Progress photos")
        Text("Private: kept only inside Nova on this phone, never uploaded. Same spot, same light, once a week.", color = C.muted, fontSize = 12.sp)
        androidx.compose.foundation.lazy.LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos.size) { i ->
                val p = photos[photos.size - 1 - i]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Thumb(p.path, Modifier.size(width = 84.dp, height = 112.dp))
                    Text(p.day.substring(5), color = C.muted, fontSize = 11.sp)
                    TextButton(onClick = { vm.deleteProgressPhoto(p) }) { Text("delete", fontSize = 11.sp) }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val dir = java.io.File(ctx.filesDir, "progress").apply { mkdirs() }
                val f = java.io.File(dir, "p_${System.currentTimeMillis()}.jpg")
                pending = f
                cam.launch(androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
            }, colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Take photo") }
            if (photos.size >= 2) OutlinedButton(onClick = { compare = true }) { Text("Compare first vs latest") }
        }
    }
    if (compare && photos.size >= 2) {
        val a = photos.first(); val b = photos.last()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { compare = false },
            title = { Text("Then vs now") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(a, b).forEach { p ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Thumb(p.path, Modifier.fillMaxWidth().height(220.dp))
                            Text(p.day + (p.weightKg?.let { " · $it kg" } ?: ""), color = C.muted, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { compare = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun Thumb(path: String, modifier: Modifier) {
    val bmp = remember(path) {
        runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
            val raw = android.graphics.BitmapFactory.decodeFile(path, o)
            // honour the camera's rotation tag
            val deg = when (androidx.exifinterface.media.ExifInterface(path).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
            if (deg == 0f) raw else android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
        }.getOrNull()
    }
    if (bmp != null) androidx.compose.foundation.Image(bmp.asImageBitmap(), null, modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else Text("?", modifier = modifier, color = C.muted)
}
