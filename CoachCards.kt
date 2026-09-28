package com.aethernova.nova.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethernova.nova.data.Progression
import com.aethernova.nova.data.SleepDebt
import java.time.LocalDate

/** Weekly AI coach review + insights + sleep debt (Life tab). */
@Composable
fun CoachCard(vm: NovaViewModel) {
    val review by vm.review.collectAsState()
    val busy by vm.reviewBusy.collectAsState()
    val insights by vm.insights.collectAsState()
    val debt by vm.sleepDebt.collectAsState()
    val slope by vm.weightSlope.collectAsState()
    val s by vm.settings.collectAsState()

    NovaCard {
        SectionTitle("Coach")
        Text("Sleep debt this week: ${"%.1f".format(debt)} h", color = if (debt >= 4) C.amber else C.text, fontWeight = FontWeight.Medium)
        LinearProgressIndicator(progress = { (debt / 10).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(vertical = 4.dp),
            color = if (debt >= 4) C.amber else C.green, trackColor = C.surface2)
        Text(SleepDebt.comment(debt), color = C.muted, fontSize = 12.sp)
        slope?.let { Text("Weight trend: ${"%+.2f".format(it)} kg/week (4 weeks)", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }

        Text("Insights from your data", color = C.teal, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        if (insights.isEmpty()) Text("Keep logging for a week or two — patterns show up once there's enough data.", color = C.muted, fontSize = 12.sp)
        insights.take(5).forEach { Text("• ${it.text}" + " " + "●".repeat(it.strength), color = C.text, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp)) }
        Text("● = how solid the evidence is", color = C.muted, fontSize = 10.sp)

        Text("Weekly review", color = C.teal, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        review?.let { p ->
            Text(p.summary, color = C.text, fontSize = 13.sp)
            val changes = listOfNotNull(
                p.kcal?.let { "Calories ${s.kcalTarget} → $it" }, p.protein?.let { "Protein ${s.proteinTarget} → $it g" },
                when (p.workout) { "harder" -> "Workouts: one level harder"; "easier" -> "Workouts: repeat this week's level"; else -> null },
            )
            if (changes.isEmpty()) Text("No changes suggested — keep going.", color = C.muted, fontSize = 12.sp)
            else {
                changes.forEach { Text("→ $it", color = C.amber, fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.acceptReview() }, colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Apply") }
                    TextButton(onClick = { vm.review.value = null }) { Text("Keep current plan") }
                }
            }
        } ?: OutlinedButton(onClick = { vm.runWeeklyReview() }, enabled = !busy) { Text(if (busy) "Reviewing…" else "Review my week") }
        Text("Changes are small and capped: never below 1,600 kcal, ±150 kcal at a time. You approve every change.", color = C.muted, fontSize = 10.sp)
    }
}

@Composable
fun ChallengeCard(vm: NovaViewModel) {
    val ctx = LocalContext.current
    var metric by remember { mutableStateOf("streak") }
    NovaCard {
        SectionTitle("Challenge a friend")
        Text("Send your brother or friends a challenge card on WhatsApp. First to beat it by Sunday buys the chai.", color = C.muted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("streak" to "🔥 Streak", "protein" to "💪 Protein", "walk" to "🚶 Walking", "workouts" to "🏋️ Workouts").forEach { (k, label) ->
                FilterChip(metric == k, { metric = k }, label = { Text(label) })
            }
        }
        Button(onClick = { vm.shareChallenge(ctx, metric) }, Modifier.padding(top = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Send challenge") }
    }
}

/** Gym log with double-progression suggestions (Body tab). */
@Composable
fun GymCard(vm: NovaViewModel) {
    val sets by vm.gymSets.collectAsState()
    var exercise by remember { mutableStateOf(Progression.exercises.first()) }
    var weight by remember { mutableStateOf("") }
    var reps by remember { mutableStateOf("") }
    val today = LocalDate.now().toString()
    NovaCard {
        SectionTitle("Gym log")
        Text("For when you move to the gym. Log each set; Nova tells you when to add weight.", color = C.muted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Progression.exercises.forEach { e -> FilterChip(exercise == e, { exercise = e }, label = { Text(e) }) }
        }
        Text(Progression.suggest(sets, exercise), color = C.amber, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        val best = sets.filter { it.exercise == exercise }.maxOfOrNull { Progression.epley1RM(it.weightKg, it.reps) }
        best?.let { Text("Estimated 1-rep max: ${"%.1f".format(it)} kg", color = C.muted, fontSize = 12.sp) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(weight, { weight = it }, Modifier.weight(1f), label = { Text("kg") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(reps, { reps = it }, Modifier.weight(1f), label = { Text("reps") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(onClick = {
                val w = weight.toDoubleOrNull(); val r = reps.toIntOrNull()
                if (w != null && r != null && r > 0) { vm.logGymSet(exercise, w, r); reps = "" }
            }, colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Log set") }
        }
        sets.filter { it.day == today }.groupBy { it.exercise }.forEach { (e, list) ->
            Column(Modifier.padding(top = 6.dp)) {
                Text(e, color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { st -> TextButton(onClick = { vm.deleteGymSet(st) }) { Text("${st.weightKg}×${st.reps}", fontSize = 12.sp) } }
                }
            }
        }
        if (sets.any { it.day == today }) Text("Tap a set to delete it.", color = C.muted, fontSize = 10.sp)
    }
}
