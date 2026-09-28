package com.aethernova.nova.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(vm: NovaViewModel) {
    val saved by vm.settings.collectAsState()
    var s by remember(saved) { mutableStateOf(saved) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NovaCard {
            SectionTitle("AI brain (Google Gemini)")
            Text("Get a free key at aistudio.google.com → Get API key. It's stored encrypted on this phone.", color = C.muted, fontSize = 12.sp)
            OutlinedTextField(s.apiKey, { s = s.copy(apiKey = it) }, Modifier.fillMaxWidth(), label = { Text("API key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(s.visionModel, { s = s.copy(visionModel = it) }, Modifier.fillMaxWidth(), label = { Text("Photo model") }, singleLine = true)
            OutlinedTextField(s.chatModel, { s = s.copy(chatModel = it) }, Modifier.fillMaxWidth(), label = { Text("Chat model (cheap, high limit)") }, singleLine = true)
            Text("If a model name stops working, copy the current Flash / Flash-Lite names from AI Studio.", color = C.muted, fontSize = 11.sp)
        }

        NudgeAndBackupSettings(vm)

        AssistantSettings()

        NovaCard {
            SectionTitle("Buddy")
            OutlinedTextField(s.buddyName, { s = s.copy(buddyName = it) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            Text("Voice language", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.voiceLang == "en-GB", { s = s.copy(voiceLang = "en-GB") }, label = { Text("English") })
                FilterChip(s.voiceLang == "ml-IN", { s = s.copy(voiceLang = "ml-IN") }, label = { Text("Malayalam") })
            }
            Text("Malayalam voice needs the Malayalam pack in Google Speech Services. Replies stay in English text.", color = C.muted, fontSize = 11.sp)
            Toggle("Speak replies out loud", s.speakReplies) { s = s.copy(speakReplies = it) }
            Toggle("Malayalam flavour — Manglish words (machane, adipoli…) and Kerala festivals", s.manglish) { s = s.copy(manglish = it) }
            Toggle("Let ${s.buddyName} wander around the screen", s.roam) { s = s.copy(roam = it) }
        }

        NovaCard {
            SectionTitle("Targets")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(s.proteinTarget.toString(), { v -> v.toIntOrNull()?.let { s = s.copy(proteinTarget = it) } }, Modifier.weight(1f), label = { Text("Protein g") }, keyboardOptions = num, singleLine = true)
                OutlinedTextField(s.kcalTarget.toString(), { v -> v.toIntOrNull()?.let { s = s.copy(kcalTarget = it) } }, Modifier.weight(1f), label = { Text("kcal") }, keyboardOptions = num, singleLine = true)
                OutlinedTextField(s.stepsTarget.toString(), { v -> v.toIntOrNull()?.let { s = s.copy(stepsTarget = it) } }, Modifier.weight(1f), label = { Text("Steps") }, keyboardOptions = num, singleLine = true)
            }
            OutlinedTextField(s.planStart, { s = s.copy(planStart = it) }, Modifier.fillMaxWidth(), label = { Text("Workout plan start (yyyy-mm-dd)") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(s.waterTarget.toString(), { v -> v.toIntOrNull()?.let { s = s.copy(waterTarget = it) } }, Modifier.weight(1f), label = { Text("Water ml") }, keyboardOptions = num, singleLine = true)
                OutlinedTextField(s.focusMinutes.toString(), { v -> v.toIntOrNull()?.let { s = s.copy(focusMinutes = it.coerceIn(5, 120)) } }, Modifier.weight(1f), label = { Text("Focus min") }, keyboardOptions = num, singleLine = true)
            }
            OutlinedTextField(s.dissertationDue, { s = s.copy(dissertationDue = it) }, Modifier.fillMaxWidth(), label = { Text("Dissertation due (yyyy-mm-dd)") }, singleLine = true)
            OutlinedTextField(s.dissWords.toString(), { v -> v.toIntOrNull()?.let { s = s.copy(dissWords = it) } }, Modifier.fillMaxWidth(),
                label = { Text("Dissertation word target (check your handbook)") }, keyboardOptions = num, singleLine = true)
        }

        NovaCard {
            SectionTitle("You & your week")
            Text("Your profile (goals, food, health notes, town) and your weekly routine drive every reminder and every AI answer.", color = C.muted, fontSize = 12.sp)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.open("setup") }) { Text("Edit profile") }
                OutlinedButton(onClick = { vm.open("routine") }) { Text("Edit my week") }
            }
            OutlinedTextField(s.currency, { s = s.copy(currency = it.take(4)) }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Currency symbol") }, singleLine = true)
            Toggle("Show liver-friendly food rating (green / amber / red)", s.liverScore) { s = s.copy(liverScore = it) }
        }

        NovaCard {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val askMic = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
                if (ok) { s = s.copy(handsFree = true); vm.saveSettings(s) }
            }
            SectionTitle("\"Hey ${s.buddyName}\" hands-free")
            Toggle("Listen for \"Hey ${s.buddyName}\" while the app is open", s.handsFree) { on ->
                if (on && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    askMic.launch(android.Manifest.permission.RECORD_AUDIO)
                else s = s.copy(handsFree = on)
            }
            Text("Desk mode: prop the phone up with Nova open — the screen stays on and you can say \"Hey ${s.buddyName}, I had two eggs\". Android doesn't let normal apps listen with the screen off. Some phones beep each time listening restarts.",
                color = C.muted, fontSize = 11.sp)
        }

        NovaCard {
            SectionTitle("Money & football")
            OutlinedTextField(if (s.monthlyBudget == 0) "" else s.monthlyBudget.toString(), { v -> s = s.copy(monthlyBudget = v.toIntOrNull() ?: 0) },
                Modifier.fillMaxWidth(), label = { Text("Monthly spending budget ${s.currency} (after rent & fixed bills)") }, keyboardOptions = num, singleLine = true)
            var team by remember { mutableStateOf(s.favouriteTeam) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(team, { team = it }, Modifier.weight(1f), label = { Text("Favourite football team") }, singleLine = true)
                androidx.compose.material3.TextButton(onClick = { vm.setTeam(team) }) { Text("Follow") }
            }
        }

        CalendarCard(vm)

        NovaCard {
            SectionTitle("Privacy & reminders")
            Toggle("Share test-result numbers with the AI", s.shareResults) { s = s.copy(shareResults = it) }
            Toggle("Daily nudges (walk, workout, evening recap)", s.reminders) { s = s.copy(reminders = it) }
            Toggle("Use the AI (sends your messages, photos and the numbers above to Google Gemini)", s.aiConsent) { s = s.copy(aiConsent = it) }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(onClick = {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(com.aethernova.nova.Legal.PRIVACY_URL))) }
                }) { Text("Privacy policy") }
                var confirm by remember { mutableStateOf(false) }
                androidx.compose.material3.TextButton(onClick = { confirm = true }) { Text("Delete all my data", color = C.red) }
                if (confirm) androidx.compose.material3.AlertDialog(onDismissRequest = { confirm = false },
                    title = { Text("Delete everything?") },
                    text = { Text("This wipes all your logs, photos, settings and your API key from this phone, then restarts ${s.buddyName}. It can't be undone — make a backup first if you want one.") },
                    confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.deleteEverything() }) { Text("Delete", color = C.red) } },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Cancel") } })
            }
        }

        if (com.aethernova.nova.BuildConfig.SELF_UPDATE) NovaCard {
            val status by vm.updateStatus.collectAsState()
            val available by vm.updateAvailable.collectAsState()
            SectionTitle("Updates")
            Text("Installed: v${vm.currentVersion}. New versions install over this one and keep all your data.", color = C.muted, fontSize = 12.sp)
            OutlinedTextField(s.updateRepo, { s = s.copy(updateRepo = it) }, Modifier.fillMaxWidth(), label = { Text("GitHub repo (owner/name)") }, singleLine = true)
            status?.let { Text(it, color = C.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            available?.notes?.takeIf { it.isNotBlank() }?.let { Text(it.take(400), color = C.muted, fontSize = 12.sp) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.saveSettings(s); vm.checkUpdate() }) { Text("Check for updates") }
                if (available != null) Button(onClick = { vm.installUpdate() },
                    colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Install v${available?.version}") }
            }
        }

        NovaCard {
            SectionTitle("Portfolio / demo mode")
            Text(if (vm.isDemo) "DEMO MODE is ON — you're looking at fake sample data. Reminders are off." else
                "Switch to 60 days of realistic fake data for screenshots, screen recordings and interviews. Your real data is kept safe in its own database and comes back when you switch off.",
                color = if (vm.isDemo) C.amber else C.muted, fontSize = 12.sp)
            OutlinedButton(onClick = { vm.setDemo(!vm.isDemo) }, Modifier.padding(top = 6.dp)) { Text(if (vm.isDemo) "Back to my real data" else "Switch to demo data (restarts)") }
        }

        Button(onClick = { vm.saveSettings(s) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Save settings") }
        OutlinedButton(onClick = { vm.clearChat() }, Modifier.fillMaxWidth()) { Text("Clear chat history") }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(value, onChange)
    }
}

@Composable
private fun CalendarCard(vm: NovaViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val saved by vm.settings.collectAsState()
    fun granted() = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var ok by remember { mutableStateOf(granted()) }
    var cals by remember { mutableStateOf(if (ok) runCatching { com.aethernova.nova.data.CalendarSync.calendars(ctx) }.getOrDefault(emptyList()) else emptyList()) }
    var chosen by remember { mutableStateOf(saved.calendarId) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        ok = r.values.all { it }
        if (ok) cals = runCatching { com.aethernova.nova.data.CalendarSync.calendars(ctx) }.getOrDefault(emptyList())
    }
    NovaCard {
        SectionTitle("Calendar sync")
        Text("Adds your weekly routine (shifts, classes, workouts, events) to your calendar for the next 2 weeks. Re-sync any time; only Nova's own events are replaced.",
            color = C.muted, fontSize = 12.sp)
        if (!ok) Button(onClick = { ask.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)) }, Modifier.padding(top = 8.dp)) { Text("Allow calendar access") }
        else {
            if (cals.isEmpty()) Text("No writable calendars found on this phone.", color = C.amber, fontSize = 13.sp)
            cals.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(chosen == c.id, { chosen = c.id })
                    Column { Text(c.name, color = C.text, fontSize = 14.sp); Text(c.account, color = C.muted, fontSize = 11.sp) }
                }
            }
            Button(onClick = { vm.syncCalendar(ctx, chosen) }, enabled = chosen >= 0, modifier = Modifier.padding(top = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Sync next 2 weeks") }
        }
    }
}

@Composable
private fun NudgeAndBackupSettings(vm: NovaViewModel) {
    val saved by vm.settings.collectAsState()
    val status by vm.backupStatus.collectAsState()
    var pw by remember { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val createFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { vm.backup(it, pw) }
    }
    val openFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        pendingRestore = uri
    }
    NovaCard {
        SectionTitle("Schedule nudges")
        Toggle("Remind me before each part of my day", saved.nudges) { vm.saveSettings(saved.copy(nudges = it)) }
        Toggle("If I don't start: second reminder LOUD (alarm sound + Nova shouting)", saved.loudNudges) { vm.saveSettings(saved.copy(loudNudges = it)) }
        Toggle("Wind-down breathing prompt 30 min after late shifts (gentle only)", saved.windDown) { vm.saveSettings(saved.copy(windDown = it)) }
        Toggle("Eye & stretch breaks every 20 min in focus blocks", saved.eyeBreaks) { vm.saveSettings(saved.copy(eyeBreaks = it)) }
        Text("Gentle first (e.g. a 17:30 shift with 25 min travel: 16:50 \"leave by 17:05\"). Tap \"On it ✓\" to stop the second one. If you don't, it checks again and shouts. " +
            "Walks and workouts are detected automatically from your logs/steps. Loud only between 05:00 and 22:30; online classes, socials and bedtime stay quiet.",
            color = C.muted, fontSize = 11.sp)
    }
    NovaCard {
        SectionTitle("Backup & restore")
        val last = com.aethernova.nova.data.Backup.lastBackup(ctx)
        Text(if (last == 0L) "Never backed up — if you lose or reset this phone, everything is gone." else
            "Last backup: " + java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
            color = if (last == 0L) C.amber else C.muted, fontSize = 12.sp)
        OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Backup password (min 6 characters)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { createFile.launch("nova-backup-${java.time.LocalDate.now()}.nova") }, enabled = pw.length >= 6,
                colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Back up now") }
            OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }, enabled = pw.length >= 6) { Text("Restore…") }
        }
        Text("Save the file to Google Drive in the picker. It holds your data, photos and settings, encrypted with this password. API keys aren't included — paste them again after a restore.",
            color = C.muted, fontSize = 11.sp)
        status?.let { Text(it, color = C.text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
    pendingRestore?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Replace everything?") },
            text = { Text("Restoring replaces ALL current data in Nova with the backup, then restarts the app.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.restore(uri, pw); pendingRestore = null }) { Text("Restore") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }
}
