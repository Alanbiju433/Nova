package com.aethernova.nova.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aethernova.nova.assist.WakeService
import com.aethernova.nova.data.Secure
import kotlinx.coroutines.delay
import java.io.File

/** Settings for using Nova like Siri: default assistant, phone control permissions, always-listening wake word. */
@Composable
fun AssistantSettings() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }            // re-check permissions when returning
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val contactsOk = remember(tick) { has(Manifest.permission.READ_CONTACTS) }
    val micOk = remember(tick) { has(Manifest.permission.RECORD_AUDIO) }
    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    val batteryOk = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    var key by remember { mutableStateOf(Secure.get(ctx, "picovoice_key")) }
    var customWord by remember { mutableStateOf(File(ctx.filesDir, "wakeword/custom.ppn").exists()) }
    val running = remember(tick) { WakeService.running }
    val err = remember(tick) { WakeService.lastError }

    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val pickPpn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val dir = File(ctx.filesDir, "wakeword").apply { mkdirs() }
            ctx.contentResolver.openInputStream(uri)!!.use { i -> File(dir, "custom.ppn").outputStream().use { i.copyTo(it) } }
            customWord = true
        }
    }
    fun open(i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    NovaCard {
        SectionTitle("Use Nova like Siri")
        Text("1. Make Nova your phone's assistant, so holding the power button opens Nova — even on the lock screen.", color = C.text, fontSize = 13.sp)
        Button(onClick = {
            open(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)).onFailure { open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        }, Modifier.padding(top = 6.dp), colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Open default apps") }
        Text("On your OPPO (ColorOS 15): Default apps → Digital assistant app → pick Nova. Then Settings → Additional settings → Power button → " +
            "Press and hold power button → Voice assistant. (Menu names can differ slightly by update.)", color = C.muted, fontSize = 11.sp)

        Text("2. Phone control: \"set an alarm for 5\", \"timer 10 minutes\", \"open WhatsApp\", \"torch on\", \"call Mum\", \"text Rahul on my way\", " +
            "\"navigate to the station\", \"play Malayalam songs\", \"remind me at 9pm to take the bins out\". Calls and texts open ready to go — you press call/send.",
            color = C.text, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        if (!contactsOk) OutlinedButton(onClick = { askContacts.launch(Manifest.permission.READ_CONTACTS) }, Modifier.padding(top = 6.dp)) { Text("Allow contacts (for call/text by name)") }
        else Text("Contacts allowed ✓", color = C.green, fontSize = 12.sp)
        Text("On a locked phone Nova only does timers, alarms, torch and volume until you unlock — nobody can ask it about your health or money from your lock screen.",
            color = C.muted, fontSize = 11.sp)
    }

    NovaCard {
        SectionTitle("Always listening (screen off)")
        Text("Say the wake word any time, even with the screen off, and Nova pops up. It runs a tiny on-device listener (Picovoice Porcupine): no audio is recorded or uploaded. " +
            "It uses a little battery and shows a permanent \"Nova is listening\" notification.", color = C.muted, fontSize = 12.sp)
        Text("Wake word: " + if (customWord) "your custom word (.ppn)" else "\"Jarvis\" (built in)", color = C.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickPpn.launch(arrayOf("*/*")) }) { Text("Import \"Hey Nova\" .ppn") }
            if (customWord) OutlinedButton(onClick = { File(ctx.filesDir, "wakeword/custom.ppn").delete(); customWord = false }) { Text("Use Jarvis") }
        }
        Text("Free AccessKey and custom \"Hey Nova\" word: console.picovoice.ai → sign up → copy AccessKey; Porcupine → type \"Hey Nova\" → Android → download .ppn.",
            color = C.muted, fontSize = 11.sp)
        OutlinedTextField(key, { key = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Picovoice AccessKey") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())

        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Check("Microphone", micOk) { askMic.launch(Manifest.permission.RECORD_AUDIO) }
            Check("Display over other apps (so Nova can pop up)", overlayOk) {
                open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
            Check("Not killed by battery saver", batteryOk) {
                // opens the system list (no special permission needed): pick Nova → Don't optimise
                open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        Text("OPPO/ColorOS also: Settings → Battery → App battery management → Nova → allow background activity and auto-launch. " +
            "After a restart, open Nova once to turn listening back on (Android rule).", color = C.muted, fontSize = 11.sp)

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (running) "Listening ✓" else "Off", color = if (running) C.green else C.muted, modifier = Modifier.weight(1f))
            Switch(running, { on ->
                Secure.put(ctx, "picovoice_key", key)
                if (on) { if (micOk) WakeService.start(ctx) else askMic.launch(Manifest.permission.RECORD_AUDIO) } else WakeService.stop(ctx)
                tick++
            }, enabled = key.isNotBlank())
        }
        err?.let { Text(it, color = C.amber, fontSize = 12.sp) }
    }
}

@Composable
private fun Check(label: String, ok: Boolean, fix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text((if (ok) "✓ " else "✗ ") + label, color = if (ok) C.green else C.amber, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (!ok) androidx.compose.material3.TextButton(onClick = fix) { Text("Fix") }
    }
}
