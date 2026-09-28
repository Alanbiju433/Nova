package com.aethernova.nova.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.aethernova.nova.data.FoodEntry
import java.io.File

@Composable
fun NovaScreen(vm: NovaViewModel, onPlay: () -> Unit = {}, onSettings: () -> Unit = {}) {
    val ctx = LocalContext.current
    val mood by vm.mood.collectAsState()
    val speaking by vm.speaker.speaking.collectAsState()
    val thinking by vm.thinking.collectAsState()
    val chat by vm.chat.collectAsState()
    val food by vm.todayFood.collectAsState()
    val vitals by vm.vitals.collectAsState()
    val s by vm.settings.collectAsState()
    val pending by vm.pendingFood.collectAsState()

    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var photoMode by remember { mutableStateOf(PhotoMode.FOOD) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var photoNote by remember { mutableStateOf("") }
    var micTarget by remember { mutableStateOf("chat") }  // chat | note
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (micTarget == "note") photoNote = heard else vm.send(heard)
        }
    }
    fun listen(target: String) {
        micTarget = target
        vm.speaker.stop()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, s.voiceLang)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to ${s.buddyName}")
        runCatching { speech.launch(i) }.onFailure { vm.toast.value = "No speech recogniser on this phone" }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { u ->
            val b = loadBitmap(ctx, u)
            if (photoMode == PhotoMode.FRIDGE) b?.let { vm.photo(it, PhotoMode.FRIDGE, "") } else photo = b
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { photo = loadBitmap(ctx, it) }
    }
    fun openCamera(mode: PhotoMode) {
        photoMode = mode
        val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
        val f = File(dir, "shot.jpg")
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        cameraUri = uri
        camera.launch(uri)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        val action by vm.action.collectAsState()
        val xp by vm.totalXp.collectAsState()
        val skin = com.aethernova.nova.data.Pet.skin(s.skin, com.aethernova.nova.data.Pet.level(xp))
        Box(Modifier.fillMaxWidth()) {
            NovaBody(
                mood, action, speaking, thinking, skin,
                Modifier.padding(top = 4.dp), height = 200.dp, roam = s.roam,
                onTap = { vm.onFaceTap() }, onLongPress = { vm.onFacePat() },
            )
            Row(Modifier.align(Alignment.TopEnd)) {
                val hf by vm.handsFreeActive.collectAsState()
                if (hf) Icon(Icons.Default.Hearing, "Listening for Hey Nova", tint = C.green, modifier = Modifier.padding(12.dp))
                IconButton(onClick = { vm.briefMe() }) { Icon(Icons.Default.WbSunny, "Morning briefing", tint = C.amber) }
                IconButton(onClick = { vm.dance() }) { Icon(Icons.Default.MusicNote, "Dance", tint = C.teal) }
                IconButton(onClick = onPlay) { Icon(Icons.Default.SportsEsports, "Play mode", tint = C.teal) }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = C.muted) }
            }
            Text("Lv ${com.aethernova.nova.data.Pet.level(xp)}", color = C.teal, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        if (thinking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 60.dp), color = C.teal, trackColor = C.surface2)

        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Ring("Protein", food.sumOf { it.proteinG }, s.proteinTarget.toDouble(), "g", C.teal)
            Ring("Calories", food.sumOf { it.kcal }.toDouble(), s.kcalTarget.toDouble(), "", C.amber)
            Ring("Steps", (vitals.stepsToday ?: 0L).toDouble(), s.stepsTarget.toDouble(), "", C.green)
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chat, key = { it.id }) { m ->
                val mine = m.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Text(
                        m.text, color = if (mine) C.bg else C.text, fontSize = 15.sp,
                        modifier = Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (mine) C.teal else C.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            if (chat.isEmpty()) item {
                Text(
                    "Say hi to ${s.buddyName}. Try: \"Had 3 dosas and 2 eggs\", \"How am I doing today?\" or snap your plate.",
                    color = C.muted, modifier = Modifier.padding(12.dp),
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.PhotoCamera, "Photo", tint = C.teal) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Food photo") }, onClick = { menu = false; openCamera(PhotoMode.FOOD) })
                    DropdownMenuItem(text = { Text("Food from gallery") }, onClick = {
                        menu = false; photoMode = PhotoMode.FOOD
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    DropdownMenuItem(text = { Text("Outfit check") }, onClick = { menu = false; openCamera(PhotoMode.OUTFIT) })
                    DropdownMenuItem(text = { Text("Fridge: what can I cook?") }, onClick = { menu = false; openCamera(PhotoMode.FRIDGE) })
                }
            }
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f),
                placeholder = { Text("Message ${s.buddyName}") }, maxLines = 3, shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.size(6.dp))
            if (input.isBlank()) {
                FilledIconButton(onClick = { listen("chat") }, Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = C.teal)) {
                    Icon(Icons.Default.Mic, "Talk", tint = C.bg)
                }
            } else {
                FilledIconButton(onClick = { vm.send(input); input = "" }, Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = C.teal)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = C.bg)
                }
            }
        }
    }

    // Photo note dialog
    photo?.let { bmp ->
        AlertDialog(
            onDismissRequest = { photo = null; photoNote = "" },
            title = { Text(if (photoMode == PhotoMode.FOOD) "What's on the plate?" else "Where are you going?") },
            text = {
                Column {
                    Text(
                        if (photoMode == PhotoMode.FOOD) "Optional but makes it way more accurate: e.g. \"3 dosas, half bowl fish curry\"."
                        else "Optional: e.g. \"uni\", \"church\", \"date\".",
                        color = C.muted, fontSize = 13.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(photoNote, { photoNote = it }, Modifier.weight(1f))
                        IconButton(onClick = { listen("note") }) { Icon(Icons.Default.Mic, "Speak", tint = C.teal) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.photo(bmp, photoMode, photoNote); photo = null; photoNote = "" }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { photo = null; photoNote = "" }) { Text("Cancel") } },
        )
    }

    pending?.let { analysis -> FoodConfirmDialog(analysis.items, analysis.comment, onSave = { vm.confirmFood(it) }, onCancel = { vm.dismissFood() }) }
}

@Composable
fun FoodConfirmDialog(items: List<FoodEntry>, comment: String, onSave: (List<FoodEntry>) -> Unit, onCancel: () -> Unit) {
    val rows = remember(items) { mutableStateListOf(*items.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Check before I log it") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text(comment, color = C.muted, fontSize = 13.sp) }
                items(rows.size) { i ->
                    val r = rows[i]
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(C.surface2).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.name} · ${r.portion}", Modifier.weight(1f), color = C.text, fontSize = 14.sp)
                            IconButton(onClick = { rows.removeAt(i) }, Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Remove", tint = C.muted) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(r.kcal.toString(), { v -> v.toIntOrNull()?.let { rows[i] = r.copy(kcal = it) } },
                                Modifier.weight(1f), label = { Text("kcal") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField("%.0f".format(r.proteinG), { v -> v.toDoubleOrNull()?.let { rows[i] = r.copy(proteinG = it) } },
                                Modifier.weight(1f), label = { Text("protein g") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }
                }
                item {
                    Text("Total: ${rows.sumOf { it.kcal }} kcal · ${"%.0f".format(rows.sumOf { it.proteinG })} g protein", color = C.teal)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(rows.toList()) }, enabled = rows.isNotEmpty()) { Text("Log it") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

fun loadBitmap(ctx: Context, uri: Uri): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val long = maxOf(info.size.width, info.size.height)
        if (long > 1600) {
            val sc = 1600f / long
            d.setTargetSize((info.size.width * sc).toInt(), (info.size.height * sc).toInt())
        }
    }
}.getOrNull()
