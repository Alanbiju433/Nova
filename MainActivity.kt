package com.aethernova.nova

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.aethernova.nova.ui.BodyScreen
import com.aethernova.nova.ui.C
import com.aethernova.nova.ui.FoodScreen
import com.aethernova.nova.ui.HealthScreen
import com.aethernova.nova.ui.LifeScreen
import com.aethernova.nova.ui.NovaScreen
import com.aethernova.nova.ui.NovaTheme
import com.aethernova.nova.ui.NovaViewModel
import com.aethernova.nova.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: NovaViewModel by viewModels()

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val healthPermission = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        vm.refreshVitals()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            NovaTheme { Root(vm) { healthPermission.launch(vm.health.permissions) } }
        }
        openFrom(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** Notifications can open a page directly (e.g. the wind-down one opens breathing). */
    private fun openFrom(i: android.content.Intent?) {
        val page = i?.getStringExtra("open") ?: return
        if (page in setOf("breathe", "flash", "cook", "mealprep")) vm.open(page)
        i.removeExtra("open")
    }

    override fun onResume() {
        super.onResume()
        (application as NovaApp).foreground = true
        vm.refreshVitals()
        val mic = androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        vm.resumeHandsFree(mic)
        // In-app "Hey Nova" and the background wake word can't share the mic
        if (vm.handsFreeActive.value) com.aethernova.nova.assist.WakeService.pause(this)
        // Desk mode: keep the screen awake while "Hey Nova" is listening
        if (vm.handsFreeActive.value) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        (application as NovaApp).foreground = false
        if (vm.handsFreeActive.value) com.aethernova.nova.assist.WakeService.resume(this)
        vm.pauseHandsFree()
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun Root(vm: NovaViewModel, connectHealth: () -> Unit) {
    val tabs = listOf(
        Tab("Nova", Icons.Default.SmartToy), Tab("Food", Icons.Default.Restaurant),
        Tab("Body", Icons.Default.FitnessCenter), Tab("Life", Icons.Default.EmojiEvents), Tab("Work", Icons.Default.Work), Tab("Health", Icons.Default.Favorite),
    )
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val overlay by vm.overlay.collectAsState()
    val snack = remember { SnackbarHostState() }
    val toast by vm.toast.collectAsState()
    val s by vm.settings.collectAsState()

    LaunchedEffect(toast) {
        toast?.let { snack.showSnackbar(it); vm.toast.value = null }
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    var crash by remember { mutableStateOf(com.aethernova.nova.CrashLog.read(ctx)) }
    crash?.let { text ->
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { com.aethernova.nova.CrashLog.clear(ctx); crash = null },
            title = { Text("Nova crashed last time") },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text("Copy this and email it to ${com.aethernova.nova.Legal.CONTACT_EMAIL} so it can be fixed. It contains no personal data, just the error.", color = C.muted, fontSize = 13.sp)
                    Text(text, fontSize = 11.sp, color = C.text, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    vm.toast.value = "Crash report copied"
                    com.aethernova.nova.CrashLog.clear(ctx); crash = null
                }) { Text("Copy report") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { com.aethernova.nova.CrashLog.clear(ctx); crash = null }) { Text("Dismiss") }
            },
        )
    }

    if (!s.onboarded && !vm.isDemo) {
        Scaffold(containerColor = C.bg, snackbarHost = { SnackbarHost(snack) }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) { com.aethernova.nova.ui.SetupScreen(vm, editing = false) { } }
        }
        return
    }

    overlay?.let { page ->
        androidx.activity.compose.BackHandler { vm.open(null) }
        Scaffold(containerColor = C.bg, snackbarHost = { SnackbarHost(snack) }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when {
                    page == "play" -> com.aethernova.nova.play.PlayScreen(vm) { vm.open(null) }
                    page == "grocery" -> com.aethernova.nova.ui.GroceryScreen(vm) { vm.open(null) }
                    page.startsWith("interview:") -> com.aethernova.nova.ui.InterviewScreen(vm, page.removePrefix("interview:")) { vm.open(null) }
                    page == "flash" -> com.aethernova.nova.ui.FlashReviewScreen(vm) { vm.open(null) }
                    page == "cook" -> com.aethernova.nova.ui.CookScreen(vm) { vm.open(null) }
                    page == "mealprep" -> com.aethernova.nova.ui.MealPrepScreen(vm) { vm.open(null) }
                    page == "breathe" -> com.aethernova.nova.ui.BreatheScreen(vm) { vm.open(null) }
                    page == "setup" -> com.aethernova.nova.ui.SetupScreen(vm, editing = true) { vm.open("settings") }
                    page == "routine" -> com.aethernova.nova.ui.RoutineScreen(vm) { vm.open("settings") }
                    page.startsWith("coach:") -> com.aethernova.nova.vision.CoachScreen(
                        vm, com.aethernova.nova.vision.Exercise.valueOf(page.removePrefix("coach:"))) { vm.open(null) }
                    else -> Column(Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.material3.IconButton(onClick = { vm.open(null) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text)
                            }
                            Text("Settings", color = C.text, fontSize = 20.sp)
                        }
                        SettingsScreen(vm)
                    }
                }
            }
        }
        return
    }

    Scaffold(
        containerColor = C.bg,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = C.surface) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i }, alwaysShowLabel = false,
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = C.bg, indicatorColor = C.teal),
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (s.apiKey.isBlank() || !s.aiConsent) {
                Text(
                    "AI is off: ${s.buddyName} works offline. To chat and read photos, add a free Gemini key in Settings. Tap here.",
                    color = C.bg, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { vm.open("settings") }.background(C.amber).padding(10.dp),
                )
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> NovaScreen(vm, onPlay = { vm.open("play") }, onSettings = { vm.open("settings") })
                    1 -> FoodScreen(vm)
                    2 -> BodyScreen(vm, connectHealth)
                    3 -> LifeScreen(vm)
                    4 -> com.aethernova.nova.ui.WorkScreen(vm)
                    else -> HealthScreen(vm)
                }
            }
        }
    }
}
