package com.aethernova.nova.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethernova.nova.reminders.MedPlan
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Composable
fun HealthExtras(vm: NovaViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MedsCard(vm)
        FollowupsCard(vm)
        DoctorCard(vm)
    }
}

@Composable
private fun MedsCard(vm: NovaViewModel) {
    val meds by vm.meds.collectAsState()
    val logs by vm.medLogs.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    NovaCard {
        SectionTitle("Supplements & medicines")
        Text("Add exactly what your pharmacist or doctor told you. Nova reminds and tracks — it never suggests doses.", color = C.muted, fontSize = 12.sp)
        val adherence = MedPlan.adherence(meds, logs, today.minusDays(13), today, LocalDateTime.now())
        adherence?.let { Text("Taken on time, last 14 days: $it%", color = if (it >= 80) C.green else C.amber, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
        meds.forEach { m ->
            Column(Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = if (m.active) C.text else C.muted, fontWeight = FontWeight.Medium)
                        Text("${m.dose} · ${m.times}${if (m.weekday != 0) " · weekly (${java.time.DayOfWeek.of(m.weekday).name.take(3)})" else ""}" +
                            (if (m.endDay.isNotBlank()) " · until ${m.endDay}" else ""), color = C.muted, fontSize = 12.sp)
                    }
                    Switch(m.active, { vm.setMedActive(m, it) })
                }
                if (MedPlan.activeOn(m, today)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Today:", color = C.muted, fontSize = 12.sp)
                    MedPlan.slots(m).forEach { slot ->
                        val taken = logs.any { it.medId == m.id && it.day == today.toString() && it.slot == slot.toString() }
                        Checkbox(taken, { vm.toggleDose(m, slot.toString()) })
                        Text(slot.toString(), color = if (taken) C.green else C.text, fontSize = 12.sp)
                    }
                }
            }
        }
        OutlinedButton(onClick = { adding = true }, Modifier.padding(top = 8.dp)) { Text("+ Add supplement / medicine") }
    }
    if (adding) AddMedDialog(onAdd = { n, d, t, w, e -> vm.addMed(n, d, t, w, e); adding = false }, onCancel = { adding = false })
}

@Composable
private fun AddMedDialog(onAdd: (String, String, String, Int, String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var times by remember { mutableStateOf("08:00") }
    var weekday by remember { mutableStateOf(0) }
    var end by remember { mutableStateOf("") }
    val timesOk = times.split(',').all { runCatching { LocalTime.parse(it.trim()) }.isSuccess }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add supplement / medicine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Vitamin D3)") }, singleLine = true)
                OutlinedTextField(dose, { dose = it }, label = { Text("Dose exactly as advised (e.g. 1 tablet)") }, singleLine = true)
                OutlinedTextField(times, { times = it }, label = { Text("Times, comma-separated (08:00,20:00)") }, singleLine = true, isError = !timesOk)
                Text("How often", color = C.muted, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(weekday == 0, { weekday = 0 }, label = { Text("Daily") })
                    (1..7).forEach { d -> FilterChip(weekday == d, { weekday = d }, label = { Text("Weekly " + java.time.DayOfWeek.of(d).name.take(3).lowercase()) }) }
                }
                OutlinedTextField(end, { end = it }, label = { Text("End date if it's a course (yyyy-mm-dd, optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && dose.isNotBlank() && timesOk && (end.isBlank() || runCatching { LocalDate.parse(end) }.isSuccess),
                onClick = { onAdd(name, dose, times.split(',').joinToString(",") { LocalTime.parse(it.trim()).toString() }, weekday, end) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun FollowupsCard(vm: NovaViewModel) {
    val items by vm.followups.collectAsState()
    var title by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<com.aethernova.nova.data.Followup?>(null) }
    NovaCard {
        SectionTitle("Tests & appointments")
        Text("Nova reminds you a week before, the day before and on the day.", color = C.muted, fontSize = 12.sp)
        items.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f.done, { vm.updateFollowup(f.copy(done = it)) })
                Column(Modifier.weight(1f)) {
                    Text(f.title, color = if (f.done) C.muted else C.text)
                    Text(f.dueDay + if (f.note.isNotBlank()) " · ${f.note}" else "", color = C.muted, fontSize = 11.sp)
                }
                TextButton(onClick = { editing = f }) { Text("date", fontSize = 11.sp) }
                TextButton(onClick = { vm.deleteFollowup(f) }) { Text("del", fontSize = 11.sp) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.weight(1.4f), label = { Text("e.g. Vitamin D recheck") }, singleLine = true)
            OutlinedTextField(due, { due = it }, Modifier.weight(1f), label = { Text("yyyy-mm-dd") }, singleLine = true)
        }
        TextButton(onClick = { vm.addFollowup(title, due, ""); title = ""; due = "" }) { Text("Add") }
    }
    editing?.let { f ->
        var d by remember(f) { mutableStateOf(f.dueDay) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(f.title) },
            text = { OutlinedTextField(d, { d = it }, label = { Text("Date (yyyy-mm-dd)") }, singleLine = true) },
            confirmButton = { TextButton(enabled = runCatching { LocalDate.parse(d) }.isSuccess, onClick = { vm.updateFollowup(f.copy(dueDay = d, note = "")); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DoctorCard(vm: NovaViewModel) {
    val ctx = LocalContext.current
    val busy by vm.reportBusy.collectAsState()
    NovaCard {
        SectionTitle("Doctor-ready summary")
        Text("A PDF of the last 90 days — weight trend, diet averages, activity, sleep, test results, supplements and follow-ups — to show at your re-test or GP.",
            color = C.muted, fontSize = 12.sp)
        Button(onClick = { vm.shareDoctorSummary(ctx) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text(if (busy) "Making PDF…" else "Create & share PDF") }
    }
}
