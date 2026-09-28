package com.aethernova.nova.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aethernova.nova.NovaApp
import com.aethernova.nova.ai.FoodAnalysis
import com.aethernova.nova.ai.Mood
import com.aethernova.nova.ai.NoKeyException
import com.aethernova.nova.ai.QuotaException
import com.aethernova.nova.ai.Vitals
import com.aethernova.nova.data.FoodEntry
import com.aethernova.nova.data.HealthResult
import com.aethernova.nova.data.NovaSettings
import com.aethernova.nova.data.WorkoutLog
import com.aethernova.nova.health.HealthData
import com.aethernova.nova.play.PlayGesture
import com.aethernova.nova.reminders.Reminders
import com.aethernova.nova.voice.Speaker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class PhotoMode { FOOD, OUTFIT, FRIDGE }

@OptIn(ExperimentalCoroutinesApi::class)
class NovaViewModel(app: Application) : AndroidViewModel(app) {
    private val nova = app as NovaApp
    val repo = nova.repo
    val brain = nova.brain
    val settingsStore = nova.settings
    val settings: StateFlow<NovaSettings> = settingsStore.state
    val speaker = Speaker(app).also { it.setLanguage(settingsStore.current.voiceLang) }
    val health = HealthData(app)

    val mood = MutableStateFlow(Mood.HAPPY)
    val thinking = MutableStateFlow(false)
    val toast = MutableStateFlow<String?>(null)
    val pendingFood = MutableStateFlow<FoodAnalysis?>(null)
    val vitals = MutableStateFlow(Vitals())
    val healthConnected = MutableStateFlow(false)

    val chat = repo.dao.recentChat(60).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val foodDay = MutableStateFlow(LocalDate.now().toString())
    val foodForDay = foodDay.flatMapLatest { repo.dao.foodForDay(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val todayFood = repo.dao.foodForDay(LocalDate.now().toString())
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val weights = repo.dao.weights().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val todayCheckIn = repo.dao.latestCheckInForDay(LocalDate.now().toString())
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val weekWorkouts = repo.dao.workoutsSince(LocalDate.now().minusDays(6).toString())
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val results = repo.dao.results().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------------- Body, pet, habits ----------------
    val action = MutableStateFlow(BodyAction.IDLE)
    val totalXp = repo.dao.totalXp().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val xpByDay = repo.dao.xpByDayFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val lastFood = repo.dao.lastFoodTime().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val waterToday = repo.dao.waterForDay(LocalDate.now().toString()).stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val moodEntries = repo.dao.moodEntries(30).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val weekFocus = repo.dao.focusSince(LocalDate.now().minusDays(6).toString()).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val report = MutableStateFlow<com.aethernova.nova.data.Report.Week?>(null)

    fun addWater(ml: Int) = viewModelScope.launch { touch(); repo.addWater(ml) }
    fun undoWater() = viewModelScope.launch { repo.undoLastWater() }
    fun refreshReport() = viewModelScope.launch { report.value = repo.weekReport() }

    // ---------------- Focus timer (Pomodoro) ----------------
    /** End time in millis while a focus/break block is running, else null. */
    val focusEndsAt = MutableStateFlow<Long?>(null)
    val focusIsBreak = MutableStateFlow(false)
    private var focusJob: kotlinx.coroutines.Job? = null
    private var focusLength = 25

    fun startFocus(minutes: Int, isBreak: Boolean = false) {
        touch()
        focusJob?.cancel()
        focusLength = minutes
        val end = System.currentTimeMillis() + minutes * 60_000L
        focusEndsAt.value = end
        focusIsBreak.value = isBreak
        if (!isBreak) mood.value = Mood.FOCUS
        com.aethernova.nova.reminders.Reminders.scheduleFocusEnd(getApplication(), end, isBreak)
        eyeJob?.cancel()
        if (!isBreak && settingsStore.current.eyeBreaks) {
            com.aethernova.nova.reminders.Reminders.scheduleEyeBreaks(getApplication(), minutes)
            eyeJob = viewModelScope.launch {
                var waited = 0
                for (off in com.aethernova.nova.data.EyeBreaks.offsets(minutes)) {
                    kotlinx.coroutines.delay((off - waited) * 60_000L); waited = off
                    perform(Step(Mood.HAPPY, 20_000, m("Eye break! Look far away for 20 seconds and stretch with me.", "Kannu rest! 20 second doore nokku, stretch cheyy."), BodyAction.STRETCH),
                        Step(Mood.FOCUS, 0))
                }
            }
        } else com.aethernova.nova.reminders.Reminders.cancelEyeBreaks(getApplication())
        focusJob = viewModelScope.launch {
            while (System.currentTimeMillis() < end) kotlinx.coroutines.delay(1000)
            focusEndsAt.value = null
            if (!isBreak) {
                repo.logFocus(minutes, "focus")
                perform(Step(Mood.PROUD, 2500, m("Focus block done! Take 5.", "Focus kazhinju! 5 minute break edukku."), BodyAction.CHEER), Step(Mood.HAPPY, 0))
            } else {
                perform(Step(Mood.HAPPY, 1500, m("Break's over — back to it?", "Break kazhinju, back to work?"), BodyAction.WAVE), Step(Mood.HAPPY, 0))
            }
        }
    }

    private var eyeJob: kotlinx.coroutines.Job? = null

    fun stopFocus() {
        focusJob?.cancel()
        eyeJob?.cancel()
        com.aethernova.nova.reminders.Reminders.cancelEyeBreaks(getApplication())
        focusEndsAt.value = null
        com.aethernova.nova.reminders.Reminders.cancelFocusEnd(getApplication())
        mood.value = Mood.HAPPY
    }

    // ---------------- Play mode / idle life ----------------
    private var lastInteraction = System.currentTimeMillis()
    private var reactionJob: kotlinx.coroutines.Job? = null
    val lastGesture = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            repo.events.collect { msg ->
                toast.value = msg
                perform(Step(Mood.PROUD, 2500, msg, BodyAction.CHEER), Step(Mood.HAPPY, 0))
            }
        }
        refreshVitals()
        checkUpdate(silent = true)
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                val idle = System.currentTimeMillis() - lastInteraction
                if (idle > 90_000 && !speaker.speaking.value && !thinking.value && reactionJob?.isActive != true && focusEndsAt.value == null) mood.value = Mood.SLEEPY
            }
        }
    }

    fun touch() {
        lastInteraction = System.currentTimeMillis()
        if (mood.value == Mood.SLEEPY) mood.value = Mood.HAPPY
    }

    fun deleteFoodAndRefresh(entry: FoodEntry) = viewModelScope.launch { repo.dao.deleteFood(entry); repo.onDataChanged?.invoke() }

    private fun m(english: String, manglish: String) = if (settingsStore.current.manglish) manglish else english

    private data class Step(val mood: Mood, val ms: Long, val say: String? = null, val act: BodyAction = BodyAction.IDLE)

    /** Runs a little animated reaction: moods + body actions in sequence, optionally speaking. */
    private fun perform(vararg steps: Step) {
        touch()
        reactionJob?.cancel()
        reactionJob = viewModelScope.launch {
            try {
                for (st in steps) {
                    mood.value = st.mood
                    action.value = st.act
                    st.say?.let { if (settingsStore.current.speakReplies) speaker.speak(it) }
                    kotlinx.coroutines.delay(st.ms)
                }
            } finally {
                action.value = BodyAction.IDLE
            }
        }
    }

    fun dance() = perform(
        Step(Mood.HAPPY, 5000, m("Let's dance!", "Chumma oru dance!"), BodyAction.DANCE),
        Step(Mood.PROUD, 1200, null, BodyAction.CHEER), Step(Mood.HAPPY, 0),
    )
    fun wave() = perform(Step(Mood.HAPPY, 1800, m("Hi ${settingsStore.current.callName}!", "Hi machane!"), BodyAction.WAVE), Step(Mood.HAPPY, 0))

    fun onGesture(g: com.aethernova.nova.play.PlayGesture) {
        lastGesture.value = g.label
        val name = settingsStore.current.buddyName
        when (g) {
            PlayGesture.FINGER_GUN -> perform(
                Step(Mood.SURPRISED, 500, m("Argh! You got me…", "Ayyo! You got me, machane…")),
                Step(Mood.DEAD, 3200), Step(Mood.SLEEPY, 700),
                Step(Mood.HAPPY, 0, m("Just kidding. I'm back!", "Just kidding da, $name is back!")),
            )
            PlayGesture.WAVE -> perform(Step(Mood.SURPRISED, 250, m("Hey ${settingsStore.current.callName}!", "Hey machane!")), Step(Mood.HAPPY, 1800, null, BodyAction.WAVE), Step(Mood.HAPPY, 0))
            PlayGesture.THUMB_UP -> perform(Step(Mood.PROUD, 1500, m("Awesome!", "Adipoli!"), BodyAction.CHEER), Step(Mood.PROUD, 0))
            PlayGesture.THUMB_DOWN -> perform(Step(Mood.WORRIED, 0, m("Aww, what did I do?", "Ayyo, what did I do?")))
            PlayGesture.PEACE -> perform(Step(Mood.CHEEKY, 2500, m("Peace!", "Peace, da!"), BodyAction.DANCE), Step(Mood.CHEEKY, 0))
            PlayGesture.LOVE -> perform(Step(Mood.LOVE, 3000, m("Love you too!", "Love you too, machane!")), Step(Mood.HAPPY, 0))
            PlayGesture.FIST -> perform(Step(Mood.SURPRISED, 200, m("Fist bump!", "Fist bump, poli!")), Step(Mood.PROUD, 1200, null, BodyAction.CHEER), Step(Mood.PROUD, 0))
            PlayGesture.POINT_UP -> perform(Step(Mood.THINKING, 2500, m("Ooh, what's up there?", "Enthaa, what's up there?")), Step(Mood.NEUTRAL, 0))
        }
    }

    /** Short reaction used by games and the coach. */
    fun react(md: Mood, act: BodyAction, say: String?, ms: Long = 1800) = perform(Step(md, ms, say, act), Step(md, 0))

    private val faceCooldown = mutableMapOf<com.aethernova.nova.vision.FaceSignal, Long>()
    fun onFaceSignal(sig: com.aethernova.nova.vision.FaceSignal) {
        val now = System.currentTimeMillis()
        if (now - (faceCooldown[sig] ?: 0L) < 20_000) return
        faceCooldown[sig] = now
        when (sig) {
            com.aethernova.nova.vision.FaceSignal.SMILE -> perform(Step(Mood.HAPPY, 1800, m("Nice smile!", "Aa chiri kollaam!"), BodyAction.WAVE), Step(Mood.HAPPY, 0))
            com.aethernova.nova.vision.FaceSignal.EYES_CLOSED -> perform(Step(Mood.SLEEPY, 2500, m("You look tired. Rest your eyes for a minute?", "Ksheenam undo machane? Kannu onnu rest cheyyu.")), Step(Mood.WORRIED, 0))
            com.aethernova.nova.vision.FaceSignal.SURPRISED -> perform(Step(Mood.SURPRISED, 1500, m("Whoa, what happened?", "Enthaa, enthu patti?")), Step(Mood.NEUTRAL, 0))
            com.aethernova.nova.vision.FaceSignal.NONE -> Unit
        }
    }

    fun onFaceTap() = perform(Step(Mood.CHEEKY, 1500, m("Hehe, that tickles!", "Hehe, ikkili aakunnu!")), Step(Mood.HAPPY, 0))
    fun onFacePat() = perform(Step(Mood.LOVE, 2500, m("Mmm… head pats.", "Mmm… sugam.")), Step(Mood.HAPPY, 0))
    fun onShake() = perform(Step(Mood.DIZZY, 2500, m("Whoa whoa, I'm dizzy!", "Ayyo, thala karangunnu!")), Step(Mood.WORRIED, 800), Step(Mood.NEUTRAL, 0))

    fun refreshVitals() = viewModelScope.launch {
        if (health.hasPermissions()) {
            healthConnected.value = true
            vitals.value = Vitals(health.stepsToday(), health.sleepLastNight())
        } else healthConnected.value = false
    }

    fun speakLine(text: String) = say(text)
    /** Voice-first screens (cooking, breathing, interviews) speak even when chat replies are muted. */
    fun speakAlways(text: String) = speaker.speak(text)

    fun logCoachSession(label: String, reps: Int, minutes: Int) = viewModelScope.launch {
        repo.logWorkout("other", minutes.coerceAtLeast(1), "$label x $reps")
        perform(Step(Mood.PROUD, 2500, m("$reps $label logged. Strong!", "$reps $label done. Poli!"), BodyAction.CHEER), Step(Mood.PROUD, 0))
    }

    private fun say(text: String) {
        if (settingsStore.current.speakReplies) speaker.speak(text)
    }

    fun send(text: String) {
        if (text.isBlank()) return
        touch()
        speaker.stop()
        viewModelScope.launch {
            thinking.value = true
            mood.value = Mood.THINKING
            val r = brain.chat(text.trim(), vitals.value) { act -> com.aethernova.nova.phone.PhoneActions.run(getApplication(), act) }
            thinking.value = false
            mood.value = r.mood
            if (r.logged.isNotEmpty()) toast.value = r.logged.joinToString(" · ")
            r.pending?.let { pendingFood.value = it }
            say(r.text)
        }
    }

    fun photo(bitmap: Bitmap, mode: PhotoMode, note: String) {
        touch()
        viewModelScope.launch {
            thinking.value = true
            mood.value = Mood.THINKING
            try {
                when (mode) {
                    PhotoMode.FOOD -> {
                        val a = brain.analyzeFood(bitmap, note)
                        mood.value = a.mood
                        if (a.items.isEmpty()) { toast.value = a.comment; say(a.comment) }
                        else pendingFood.value = a
                    }
                    PhotoMode.OUTFIT -> {
                        val r = brain.outfitCheck(bitmap, note)
                        mood.value = r.mood
                        say(r.text)
                    }
                    PhotoMode.FRIDGE -> {
                        val r = brain.fridgeIdeas(bitmap)
                        mood.value = r.mood
                        fridgeIdeas.value = r.text
                        say(r.text)
                    }
                }
            } catch (e: NoKeyException) {
                mood.value = Mood.SLEEPY; toast.value = "Add your Gemini key in Settings to use photos."
            } catch (e: QuotaException) {
                mood.value = Mood.SLEEPY; toast.value = "Photo limit hit for today. Type or say what you ate instead."
            } catch (e: Exception) {
                mood.value = Mood.WORRIED; toast.value = "Photo failed: ${e.message?.take(80)}"
            } finally {
                thinking.value = false
            }
        }
    }

    fun confirmFood(items: List<FoodEntry>) = viewModelScope.launch {
        val comment = pendingFood.value?.comment.orEmpty()
        repo.addFood(items)
        pendingFood.value = null
        val (kcal, protein, _) = repo.todayTotals()
        val line = "$comment Now at ${"%.0f".format(protein)} g protein, $kcal kcal.".trim()
        repo.dao.insertChat(com.aethernova.nova.data.ChatMessage(timestamp = System.currentTimeMillis(), role = "user", text = "[food photo] " + items.joinToString { it.name }))
        repo.dao.insertChat(com.aethernova.nova.data.ChatMessage(timestamp = System.currentTimeMillis(), role = "nova", text = line))
        say(line)
    }

    fun dismissFood() { pendingFood.value = null }

    fun addFood(entry: FoodEntry) = viewModelScope.launch { repo.addFood(listOf(entry)) }
    fun deleteFood(entry: FoodEntry) = viewModelScope.launch { repo.dao.deleteFood(entry) }

    fun saveCheckIn(sleep: Double?, moodV: Int?, energy: Int?, weight: Double?, note: String = "") = viewModelScope.launch {
        repo.logCheckIn(sleep, moodV, energy, weight, note)
        toast.value = "Check-in saved"
        mood.value = if ((moodV ?: 3) <= 2 || (sleep ?: 7.0) < 6) Mood.WORRIED else Mood.HAPPY
    }

    fun logWorkout(kind: String, minutes: Int) = viewModelScope.launch {
        repo.logWorkout(kind, minutes)
        perform(Step(Mood.PROUD, 2000, null, BodyAction.CHEER), Step(Mood.PROUD, 0))
        toast.value = if (kind == "walk") "Walk logged — $minutes min" else "Session $kind done. Adipoli!"
    }
    fun deleteWorkout(w: WorkoutLog) = viewModelScope.launch { repo.dao.deleteWorkout(w) }

    fun addResult(r: HealthResult) = viewModelScope.launch { repo.dao.insertResult(r) }
    fun deleteResult(r: HealthResult) = viewModelScope.launch { repo.dao.deleteResult(r) }

    fun saveSettings(s: NovaSettings) {
        settingsStore.save(s)
        speaker.setLanguage(s.voiceLang)
        if (s.reminders) Reminders.scheduleAll(getApplication()) else Reminders.cancelAll(getApplication())
        val mic = androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (s.handsFree) resumeHandsFree(mic) else pauseHandsFree()
        com.aethernova.nova.reminders.Nudges.scheduleNext(getApplication())
        toast.value = "Saved"
    }

    // ---------------- Updates ----------------
    val updateStatus = MutableStateFlow<String?>(null)
    val updateAvailable = MutableStateFlow<com.aethernova.nova.update.Updater.Release?>(null)
    val currentVersion = com.aethernova.nova.update.Updater.currentVersion(app)

    fun checkUpdate(silent: Boolean = false) = viewModelScope.launch {
        if (!com.aethernova.nova.BuildConfig.SELF_UPDATE) return@launch   // Play build: updates come from Google Play
        if (!silent) updateStatus.value = "Checking…"
        try {
            val r = com.aethernova.nova.update.Updater.check(getApplication(), settingsStore.current.updateRepo)
            updateAvailable.value = r
            updateStatus.value = if (r == null) "You're on the latest version ($currentVersion)." else "Version ${r.version} is available."
            if (silent && r != null) toast.value = "Nova ${r.version} is available — update in Settings"
        } catch (e: Exception) {
            if (!silent) updateStatus.value = e.message ?: "Update check failed"
        }
    }

    fun installUpdate() = viewModelScope.launch {
        if (!com.aethernova.nova.BuildConfig.SELF_UPDATE) return@launch
        val r = updateAvailable.value ?: return@launch
        try {
            val file = com.aethernova.nova.update.Updater.download(getApplication(), r.apkUrl) { p -> updateStatus.value = "Downloading… $p%" }
            updateStatus.value = "Downloaded. Tap Install if Android asks."
            if (!com.aethernova.nova.update.Updater.install(getApplication(), file))
                updateStatus.value = "Allow \"Install unknown apps\" for Nova, come back and tap the button again."
        } catch (e: Exception) {
            updateStatus.value = "Update failed: ${e.message}"
        }
    }

    // ---------------- Navigation to full-screen pages ----------------
    /** null = tabs; otherwise "play", "settings", "grocery", "coach:SQUAT" / "coach:PUSHUP" / "coach:POSTURE". */
    val overlay = MutableStateFlow<String?>(null)
    fun open(page: String?) { overlay.value = page }

    val fridgeIdeas = MutableStateFlow<String?>(null)

    // ---------------- Barcode ----------------
    val scannedProduct = MutableStateFlow<com.aethernova.nova.data.FoodFacts.Product?>(null)

    fun scanBarcode(ctx: android.content.Context) {
        touch()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(ctx).startScan()
            .addOnSuccessListener { code ->
                val raw = code.rawValue ?: return@addOnSuccessListener
                viewModelScope.launch {
                    thinking.value = true
                    val p = runCatching { com.aethernova.nova.data.FoodFacts.lookup(raw) }.getOrNull()
                    thinking.value = false
                    if (p == null) { toast.value = "Not in Open Food Facts ($raw). Add it manually or snap a photo."; mood.value = Mood.WORRIED }
                    else {
                        scannedProduct.value = p
                        if (p.highSugar) { mood.value = Mood.WORRIED; say(m("Careful — that's high in sugar.", "Ayyo, athil sugar kooduthalaa.")) }
                    }
                }
            }
            .addOnFailureListener { toast.value = "Scanner unavailable: ${it.message?.take(60)}" }
    }

    fun logScanned(grams: Double) = viewModelScope.launch {
        val p = scannedProduct.value ?: return@launch
        repo.addFood(listOf(p.toEntry(grams, repo.today(), System.currentTimeMillis())))
        scannedProduct.value = null
        toast.value = "Logged ${p.name}"
    }

    // ---------------- Grocery list ----------------
    val groceries = repo.dao.groceries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val groceryBusy = MutableStateFlow(false)

    fun generateGroceries() = viewModelScope.launch {
        groceryBusy.value = true
        val items = try { brain.groceryList() } catch (e: Exception) {
            toast.value = "AI unavailable — added your staples instead."
            listOf("Whole milk" to "4 L", "Eggs" to "30", "Chicken breast" to "1 kg", "Fish or squid" to "600 g", "Atta" to "1 bag",
                "Rice" to "1 kg", "Oats" to "1 kg", "Bananas" to "7", "Apples/pears" to "6", "Skyr or Greek yogurt" to "4 pots",
                "Almonds" to "200 g", "Whole-wheat wraps" to "1 pack", "Onions, tomatoes, garlic" to "", "Spinach/greens" to "2 bags")
        }
        repo.dao.insertGrocery(items.map { com.aethernova.nova.data.GroceryItem(name = it.first, qty = it.second) })
        groceryBusy.value = false
    }
    fun toggleGrocery(i: com.aethernova.nova.data.GroceryItem) = viewModelScope.launch { repo.dao.updateGrocery(i.copy(done = !i.done)) }
    fun addGrocery(name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.dao.insertGrocery(listOf(com.aethernova.nova.data.GroceryItem(name = name.trim()))) }
    fun deleteGrocery(i: com.aethernova.nova.data.GroceryItem) = viewModelScope.launch { repo.dao.deleteGrocery(i) }
    fun clearDoneGroceries() = viewModelScope.launch { repo.dao.clearDoneGrocery() }

    // ---------------- Progress photos ----------------
    val photos = repo.dao.photos().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addProgressPhoto(path: String) = viewModelScope.launch {
        val w = weights.value.lastOrNull()?.weightKg
        repo.dao.insertPhoto(com.aethernova.nova.data.ProgressPhoto(timestamp = System.currentTimeMillis(), day = repo.today(), path = path, weightKg = w))
        perform(Step(Mood.PROUD, 1500, m("Progress photo saved. Future you will thank you.", "Photo save aayi. Progress kaanaam!"), BodyAction.CHEER), Step(Mood.HAPPY, 0))
    }
    fun deleteProgressPhoto(p: com.aethernova.nova.data.ProgressPhoto) = viewModelScope.launch {
        runCatching { java.io.File(p.path).delete() }
        repo.dao.deletePhoto(p)
    }

    // ---------------- Money ----------------
    val monthExpenses = repo.dao.expensesSince(LocalDate.now().withDayOfMonth(1).toString())
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun addExpense(amount: Double, category: String, note: String) = viewModelScope.launch {
        if (amount > 0) { repo.addExpense(amount, category, note); toast.value = "${com.aethernova.nova.data.Money.sym}${"%.2f".format(amount)} logged" }
    }
    fun deleteExpense(e: com.aethernova.nova.data.Expense) = viewModelScope.launch { repo.dao.deleteExpense(e) }

    // ---------------- Personal memory ----------------
    val facts = repo.dao.facts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun forget(f: com.aethernova.nova.data.MemoryFact) = viewModelScope.launch { repo.dao.deleteFact(f) }

    // ---------------- Football ----------------
    val football = MutableStateFlow<Pair<List<com.aethernova.nova.data.Football.Match>, List<com.aethernova.nova.data.Football.Match>>?>(null)
    fun setTeam(name: String) = viewModelScope.launch {
        val t = com.aethernova.nova.data.Football.findTeam(name)
        if (t == null) { toast.value = "Couldn't find \"$name\""; return@launch }
        settingsStore.save(settingsStore.current.copy(favouriteTeam = t.second, teamId = t.first))
        toast.value = "Following ${t.second}"
        refreshFootball()
    }
    fun refreshFootball() = viewModelScope.launch {
        val id = settingsStore.current.teamId.ifBlank { return@launch }
        football.value = com.aethernova.nova.data.Football.last(id).take(3) to com.aethernova.nova.data.Football.next(id).take(3)
    }

    // ---------------- Morning briefing & festivals ----------------
    fun briefMe() = viewModelScope.launch {
        touch()
        thinking.value = true
        val s = settingsStore.current
        val today = LocalDate.now()
        val hours = com.aethernova.nova.data.Weather.today(s.lat, s.lon)
        val walk = hours?.let { com.aethernova.nova.data.Weather.window(it, if (java.time.LocalTime.now().hour < 6) today else today.plusDays(1), 5, 6) }
        val evening = hours?.let { com.aethernova.nova.data.Weather.window(it, today, 21, 22) }
        val next = if (s.teamId.isNotBlank()) com.aethernova.nova.data.Football.next(s.teamId).firstOrNull()?.let { "${it.title} on ${it.date} at ${it.time}" } else null
        val session = com.aethernova.nova.data.Plan.sessionFor(today)?.title
        val due = runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(s.dissertationDue)) }.getOrNull()
        val text = com.aethernova.nova.data.Briefing.build(today, s.callName, s.manglish, walk, evening, session, s.proteinTarget, due,
            com.aethernova.nova.data.Festivals.today(today, s.manglish), next) + workLine(today)
        thinking.value = false
        repo.dao.insertChat(com.aethernova.nova.data.ChatMessage(timestamp = System.currentTimeMillis(), role = "nova", text = text))
        perform(Step(Mood.HAPPY, 2000, text, BodyAction.WAVE), Step(Mood.HAPPY, 0))
    }

    /** Dissertation milestone, flashcards due and close job deadlines, for the briefing. */
    private suspend fun workLine(today: LocalDate): String {
        val parts = mutableListOf<String>()
        repo.dao.nextMilestone()?.let { m ->
            val d = runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(m.dueDay)) }.getOrNull()
            if (d != null && d <= 21) parts += if (d < 0) "\"${m.title}\" is overdue" else "\"${m.title}\" due in $d days"
        }
        val due = com.aethernova.nova.data.Leitner.due(cards.value, today).size
        if (due > 0) parts += "$due flashcards to review"
        repo.dao.jobsWithDeadlinesNow().mapNotNull { j -> runCatching { j to java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(j.deadline)) }.getOrNull() }
            .filter { it.second in 0..3 }.forEach { (j, d) -> parts += "${j.company} application closes ${if (d == 0L) "today" else "in $d days"}" }
        return if (parts.isEmpty()) "" else " Work: " + parts.joinToString("; ") + "."
    }

    /** First open of the day after 5 AM: automatic briefing. Later in the day: just a festival greeting. */
    private fun dailyHello() {
        val prefs = getApplication<Application>().getSharedPreferences("nova_daily", android.content.Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString("hello_day", "") == today) return
        prefs.edit().putString("hello_day", today).apply()
        val hour = java.time.LocalTime.now().hour
        if (hour in 5..11) briefMe()
        else com.aethernova.nova.data.Festivals.today(LocalDate.now(), settingsStore.current.manglish)?.let {
            perform(Step(Mood.HAPPY, 2500, it, BodyAction.DANCE), Step(Mood.HAPPY, 0))
        }
    }

    fun tellJoke() = perform(Step(Mood.CHEEKY, 3000, com.aethernova.nova.data.Jokes.random(), BodyAction.IDLE), Step(Mood.HAPPY, 0))

    // ---------------- Hey Nova (hands-free while the app is open) ----------------
    val handsFreeActive = MutableStateFlow(false)
    private val handsFree = com.aethernova.nova.voice.HandsFree(
        app,
        language = { settingsStore.current.voiceLang },
        buddyName = { settingsStore.current.buddyName },
        isSpeaking = { speaker.speaking.value || thinking.value },
        onWake = { perform(Step(Mood.SURPRISED, 300, null, BodyAction.WAVE), Step(Mood.HAPPY, 1500, m("Yes?", "Enthaa machane?"), BodyAction.WAVE), Step(Mood.HAPPY, 0)) },
        onCommand = { cmd -> send(cmd) },
    )
    fun resumeHandsFree(micGranted: Boolean) {
        if (settingsStore.current.handsFree && micGranted && handsFree.available) { handsFree.start(); handsFreeActive.value = true }
    }
    fun pauseHandsFree() { handsFree.stop(); handsFreeActive.value = false }

    // ---------------- Calendar ----------------
    fun syncCalendar(ctx: android.content.Context, calId: Long) = viewModelScope.launch {
        val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.aethernova.nova.data.CalendarSync.sync(ctx, calId) }.getOrElse { -1 }
        }
        toast.value = if (n >= 0) "Added $n routine events for the next 2 weeks" else "Calendar sync failed"
        if (n > 0) settingsStore.save(settingsStore.current.copy(calendarId = calId))
    }

    // ---------------- Supplements & meds ----------------
    val meds = repo.dao.meds().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val medLogs = repo.dao.medLogsSince(LocalDate.now().minusDays(30).toString()).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addMed(name: String, dose: String, times: String, weekday: Int, endDay: String) = viewModelScope.launch {
        val m = com.aethernova.nova.data.Med(name = name.trim(), dose = dose.trim(), times = times, weekday = weekday,
            startDay = LocalDate.now().toString(), endDay = endDay.trim())
        val id = repo.dao.insertMed(m)
        val saved = m.copy(id = id)
        com.aethernova.nova.reminders.MedPlan.slots(saved).forEach { com.aethernova.nova.reminders.MedReminders.schedule(getApplication(), saved, it) }
        toast.value = "${m.name} added — reminders set"
    }
    fun setMedActive(m: com.aethernova.nova.data.Med, active: Boolean) = viewModelScope.launch {
        val u = m.copy(active = active); repo.dao.updateMed(u)
        if (active) com.aethernova.nova.reminders.MedPlan.slots(u).forEach { com.aethernova.nova.reminders.MedReminders.schedule(getApplication(), u, it) }
        else com.aethernova.nova.reminders.MedReminders.cancel(getApplication(), u)
    }
    fun toggleDose(m: com.aethernova.nova.data.Med, slot: String) = viewModelScope.launch {
        val today = LocalDate.now().toString()
        val existing = medLogs.value.firstOrNull { it.medId == m.id && it.day == today && it.slot == slot }
        if (existing != null) repo.dao.deleteMedLog(existing)
        else { repo.dao.insertMedLog(com.aethernova.nova.data.MedLog(medId = m.id, day = today, slot = slot, takenAt = System.currentTimeMillis())); mood.value = Mood.PROUD }
    }

    // ---------------- Follow-ups ----------------
    val followups = repo.dao.followups().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun addFollowup(title: String, due: String, note: String) = viewModelScope.launch {
        if (title.isNotBlank() && runCatching { LocalDate.parse(due) }.isSuccess)
            repo.dao.insertFollowup(com.aethernova.nova.data.Followup(title = title.trim(), dueDay = due, note = note.trim()))
    }
    fun updateFollowup(f: com.aethernova.nova.data.Followup) = viewModelScope.launch { repo.dao.updateFollowup(f) }
    fun deleteFollowup(f: com.aethernova.nova.data.Followup) = viewModelScope.launch { repo.dao.deleteFollowup(f) }

    // ---------------- Doctor summary ----------------
    val reportBusy = MutableStateFlow(false)
    fun shareDoctorSummary(ctx: android.content.Context) = viewModelScope.launch {
        reportBusy.value = true
        try {
            val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.aethernova.nova.data.DoctorReport.build(getApplication(), repo, settingsStore.current.userName.ifBlank { "Nova user" }) }
            com.aethernova.nova.data.DoctorReport.share(ctx, f)
        } catch (e: Exception) { toast.value = "Couldn't make the PDF: ${e.message?.take(60)}" }
        reportBusy.value = false
    }

    // ---------------- Backup ----------------
    val backupStatus = MutableStateFlow<String?>(null)
    fun backup(uri: android.net.Uri, password: String) = viewModelScope.launch {
        backupStatus.value = "Backing up…"
        backupStatus.value = try {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.aethernova.nova.data.Backup.export(getApplication(), uri, password) }
            "Backed up ✓ (${bytes / 1024} KB). Keep the password safe — without it the file can't be opened."
        } catch (e: Exception) { "Backup failed: ${e.message}" }
    }
    fun restore(uri: android.net.Uri, password: String) = viewModelScope.launch {
        backupStatus.value = "Restoring… Nova will restart."
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.aethernova.nova.data.Backup.restore(getApplication(), uri, password) }
        } catch (e: Exception) { backupStatus.value = e.message ?: "Restore failed" }
    }

    // ---------------- Insights, sleep debt, weekly review ----------------
    val insights = MutableStateFlow<List<com.aethernova.nova.data.Insights.Insight>>(emptyList())
    val sleepDebt = MutableStateFlow(0.0)
    val weightSlope = MutableStateFlow<Double?>(null)

    fun refreshInsights() = viewModelScope.launch {
        val today = LocalDate.now()
        val from = today.minusDays(60).toString()
        val checks = repo.dao.checkInsSinceDay(from)
        val food = repo.dao.dailyTotalsSince(from)
        val workouts = repo.dao.workoutsSinceNow(from)
        val foods = repo.dao.foodSince(today.minusDays(13).toString())
        insights.value = com.aethernova.nova.data.Insights.compute(today, food, checks, workouts, settingsStore.current.proteinTarget, foods, settingsStore.current.liverScore)
        sleepDebt.value = com.aethernova.nova.data.SleepDebt.debt(com.aethernova.nova.data.SleepDebt.nights(checks), today)
        weightSlope.value = com.aethernova.nova.data.Insights.weightSlopePerWeek(
            checks.filter { it.weightKg != null && !LocalDate.parse(it.day).isBefore(today.minusDays(28)) }.map { LocalDate.parse(it.day) to it.weightKg!! })
    }

    val review = MutableStateFlow<com.aethernova.nova.data.WeeklyRules.Proposal?>(null)
    val reviewBusy = MutableStateFlow(false)
    fun runWeeklyReview() = viewModelScope.launch {
        reviewBusy.value = true
        refreshInsights().join()
        val w = repo.weekReport()
        val p = brain.weeklyReview(w, insights.value.take(4).map { it.text }, weightSlope.value)
        review.value = p
        reviewBusy.value = false
        perform(Step(Mood.THINKING, 800), Step(Mood.HAPPY, 2000, p.summary, BodyAction.WAVE), Step(Mood.HAPPY, 0))
    }
    fun acceptReview() {
        val p = review.value ?: return
        var s = settingsStore.current
        p.kcal?.let { s = s.copy(kcalTarget = it) }
        p.protein?.let { s = s.copy(proteinTarget = it) }
        // harder = move one week further along the plan; easier = repeat a week
        val start = runCatching { LocalDate.parse(s.planStart) }.getOrDefault(LocalDate.now())
        s = when (p.workout) { "harder" -> s.copy(planStart = start.minusDays(7).toString()); "easier" -> s.copy(planStart = start.plusDays(7).toString()); else -> s }
        saveSettings(s)
        review.value = null
        toast.value = "Next week's plan updated"
    }

    // ---------------- Gym ----------------
    val gymSets = repo.dao.gymSetsSince(LocalDate.now().minusDays(60).toString()).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun logGymSet(exercise: String, weight: Double, reps: Int) = viewModelScope.launch {
        repo.dao.insertGymSet(com.aethernova.nova.data.GymSet(timestamp = System.currentTimeMillis(), day = repo.today(), exercise = exercise, weightKg = weight, reps = reps))
        val todays = gymSets.value.count { it.day == repo.today() } + 1
        if (todays == 1) repo.logWorkout("gym", 45)   // first set of the day counts the session for XP & nudges
        mood.value = Mood.PROUD
    }
    fun deleteGymSet(s: com.aethernova.nova.data.GymSet) = viewModelScope.launch { repo.dao.deleteGymSet(s) }

    // ---------------- Friend challenge ----------------
    fun shareChallenge(ctx: android.content.Context, metric: String) = viewModelScope.launch {
        val w = repo.weekReport()
        val xpDays = repo.dao.xpByDay().associate { it.day to it.xp }
        val streak = com.aethernova.nova.data.Pet.streak(xpDays, LocalDate.now())
        val line = when (metric) {
            "protein" -> "I averaged ${w.avgProtein.toInt()} g protein a day this week 💪"
            "walk" -> "I walked ${w.walkMin} minutes this week 🚶"
            "workouts" -> "I smashed ${w.sessions} workouts this week 🏋️"
            else -> "I'm on a $streak-day health streak 🔥"
        }
        val text = "Nova challenge: $line — beat that by Sunday? Loser buys the chai ☕\n(sent from my AI health buddy Nova)"
        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, text), "Challenge a friend").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------------- Demo mode ----------------
    val isDemo = (app as NovaApp).demo
    /** Saves the setup / profile screens. Keeps targets above the safe floor. */
    fun finishSetup(ns: NovaSettings, startWeight: Double?) = viewModelScope.launch {
        val first = !settingsStore.current.onboarded
        val fixed = ns.copy(kcalTarget = ns.kcalTarget.coerceAtLeast(ns.kcalFloor),
            planStart = if (first) LocalDate.now().toString() else ns.planStart)
        saveSettings(fixed)
        startWeight?.let { repo.logStartWeight(it) }
        if (first) perform(Step(Mood.HAPPY, 2500, m("Hi ${fixed.callName}! I'm ${fixed.buddyName}. Let's do this.", "Hi machane! Njan ${fixed.buddyName}. Namukku thudangaam!"), BodyAction.WAVE), Step(Mood.HAPPY, 0))
    }

    fun saveRoutine(r: com.aethernova.nova.data.Routine) = saveSettings(settingsStore.current.copy(routineJson = r.toJson().toString()))

    /** Wipes every byte Nova stored on this phone (databases, settings, key, photos), then restarts fresh. */
    fun deleteEverything() {
        val ctx = getApplication<Application>()
        runCatching { androidx.work.WorkManager.getInstance(ctx).cancelAllWork() }
        runCatching { nova.db.close() }
        listOf("databases", "shared_prefs", "files", "cache", "no_backup").forEach { runCatching { java.io.File(ctx.dataDir, it).deleteRecursively() } }
        ctx.startActivity(android.content.Intent(ctx, com.aethernova.nova.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }

    fun setDemo(on: Boolean) = com.aethernova.nova.data.DemoData.switch(getApplication(), on)

    fun clearChat() = viewModelScope.launch { repo.dao.clearChat() }

    // ================= v0.9: Work tab =================
    // ---- Jobs & mock interviews ----
    val jobs = repo.dao.jobs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun addJob(company: String, role: String, deadline: String, link: String) = viewModelScope.launch {
        if (company.isBlank() && role.isBlank()) return@launch
        val d = deadline.trim().takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: ""
        repo.dao.insertJob(com.aethernova.nova.data.JobApp(company = company.trim().take(60), role = role.trim().take(80), deadline = d, link = link.trim().take(300)))
    }
    fun setJobStatus(j: com.aethernova.nova.data.JobApp, status: String) = viewModelScope.launch {
        repo.dao.updateJob(j.copy(status = status, updatedAt = System.currentTimeMillis()))
        if (status == "offer") perform(Step(Mood.PROUD, 3000, m("AN OFFER! I'm so proud of you, ${settingsStore.current.callName}!", "OFFER kitti! Poli machane!"), BodyAction.DANCE), Step(Mood.PROUD, 0))
        if (status == "interview") perform(Step(Mood.HAPPY, 2500, m("Interview! Let's practise — tap Practice.", "Interview kitti! Namukku practice cheyyaam."), BodyAction.CHEER), Step(Mood.HAPPY, 0))
    }
    fun updateJobNotes(j: com.aethernova.nova.data.JobApp, notes: String) = viewModelScope.launch { repo.dao.updateJob(j.copy(notes = notes.take(1000))) }
    fun deleteJob(j: com.aethernova.nova.data.JobApp) = viewModelScope.launch { repo.dao.deleteJob(j) }

    data class InterviewState(val role: String, val company: String, val questions: List<String> = emptyList(), val index: Int = 0,
        val answers: List<String> = emptyList(), val feedback: List<com.aethernova.nova.data.Interview.Feedback> = emptyList(), val busy: Boolean = false) {
        val done get() = questions.isNotEmpty() && feedback.size >= questions.size
        val current get() = questions.getOrNull(index)
    }
    val interview = MutableStateFlow<InterviewState?>(null)

    fun startInterview(role: String, company: String) = viewModelScope.launch {
        interview.value = InterviewState(role, company, busy = true)
        val qs = brain.interviewQuestions(role, company)
        interview.value = InterviewState(role, company, qs)
        speaker.speak(qs.first())
    }
    fun answerInterview(answer: String) = viewModelScope.launch {
        val st = interview.value ?: return@launch
        val q = st.current ?: return@launch
        interview.value = st.copy(busy = true)
        val fb = brain.interviewFeedback(st.role, q, answer)
        interview.value = st.copy(answers = st.answers + answer, feedback = st.feedback + fb, busy = false)
        speaker.speak("${fb.score} out of 10. ${fb.tips.firstOrNull().orEmpty()}")
    }
    fun nextQuestion() {
        val st = interview.value ?: return
        if (st.index + 1 < st.questions.size) { interview.value = st.copy(index = st.index + 1); speaker.speak(st.questions[st.index + 1]) }
    }
    fun endInterview() { speaker.stop(); interview.value = null }

    // ---- Dissertation ----
    val milestones = repo.dao.milestones().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val dissLogs = repo.dao.dissLogs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun setupDissertation(due: String, words: Int) = viewModelScope.launch {
        val d = runCatching { LocalDate.parse(due.trim()) }.getOrNull()
        if (d == null || d.isBefore(LocalDate.now())) { toast.value = "Enter a future date as yyyy-mm-dd"; return@launch }
        settingsStore.save(settingsStore.current.copy(dissertationDue = d.toString(), dissWords = words.coerceIn(1000, 50000)))
        repo.seedMilestones(d.toString())
    }
    fun toggleMilestone(ms: com.aethernova.nova.data.DissMilestone) = viewModelScope.launch {
        repo.dao.updateMilestone(ms.copy(done = !ms.done))
        if (!ms.done) perform(Step(Mood.PROUD, 2500, m("Milestone done: ${ms.title}!", "${ms.title} kazhinju! Poli!"), BodyAction.CHEER), Step(Mood.HAPPY, 0))
    }
    fun saveMilestone(ms: com.aethernova.nova.data.DissMilestone?, title: String, due: String) = viewModelScope.launch {
        val d = due.trim().takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: run { toast.value = "Date must be yyyy-mm-dd"; return@launch }
        if (ms == null) repo.dao.insertMilestone(com.aethernova.nova.data.DissMilestone(title = title.trim().take(80), dueDay = d))
        else repo.dao.updateMilestone(ms.copy(title = title.trim().take(80).ifBlank { ms.title }, dueDay = d))
    }
    fun deleteMilestone(ms: com.aethernova.nova.data.DissMilestone) = viewModelScope.launch { repo.dao.deleteMilestone(ms) }
    fun logWords(total: Int) = viewModelScope.launch {
        if (total <= 0) return@launch
        val prev = dissLogs.value.lastOrNull { it.kind == "words" }?.words ?: 0
        repo.dao.insertDissLog(com.aethernova.nova.data.DissLog(day = repo.today(), kind = "words", words = total.coerceAtMost(100_000)))
        if (total > prev) { repo.award("focus"); perform(Step(Mood.PROUD, 2000, m("+${total - prev} words. Keep going!", "+${total - prev} words! Adipoli!"), BodyAction.CHEER), Step(Mood.HAPPY, 0)) }
    }
    fun addMeetingNote(text: String) = viewModelScope.launch {
        if (text.isNotBlank()) repo.dao.insertDissLog(com.aethernova.nova.data.DissLog(day = repo.today(), kind = "meeting", text = text.trim().take(2000)))
    }
    fun deleteDissLog(l: com.aethernova.nova.data.DissLog) = viewModelScope.launch { repo.dao.deleteDissLog(l) }

    // ---- Flashcards ----
    val cards = repo.dao.cards().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val cardBusy = MutableStateFlow(false)
    fun addCard(deck: String, front: String, back: String) = viewModelScope.launch {
        if (front.isBlank() || back.isBlank()) return@launch
        repo.dao.insertCards(listOf(com.aethernova.nova.data.Leitner.newCard(deck, front, back, LocalDate.now())))
    }
    fun reviewCard(c: com.aethernova.nova.data.Flashcard, correct: Boolean) = viewModelScope.launch {
        repo.dao.updateCard(com.aethernova.nova.data.Leitner.review(c, correct, LocalDate.now()))
    }
    fun deleteCard(c: com.aethernova.nova.data.Flashcard) = viewModelScope.launch { repo.dao.deleteCard(c) }
    fun cardsFromPhoto(bitmap: Bitmap, deck: String) = viewModelScope.launch {
        cardBusy.value = true; mood.value = Mood.THINKING
        try {
            val made = brain.flashcardsFromPhoto(bitmap, deck)
            repo.dao.insertCards(made.map { com.aethernova.nova.data.Leitner.newCard(deck, it.first, it.second, LocalDate.now()) })
            toast.value = if (made.isEmpty()) "Couldn't read any cards from that photo" else "${made.size} cards added to ${deck.ifBlank { "General" }}"
            mood.value = if (made.isEmpty()) Mood.WORRIED else Mood.PROUD
        } catch (e: Exception) {
            toast.value = when (e) { is NoKeyException -> "Add your Gemini key in Settings first"; is QuotaException -> "AI limit reached for today — add cards by hand"; else -> "Couldn't make cards: ${e.message?.take(80)}" }
            mood.value = Mood.WORRIED
        }
        cardBusy.value = false
    }
    fun finishedReview(n: Int) { if (n > 0) viewModelScope.launch { repo.award("focus"); perform(Step(Mood.PROUD, 2000, m("$n cards reviewed. Brain gains!", "$n cards kazhinju. Brain gains!"), BodyAction.CHEER), Step(Mood.HAPPY, 0)) } }

    // ---- Shift pay ----
    val shifts = repo.dao.shiftsSince(LocalDate.now().minusMonths(2).withDayOfMonth(1).toString())
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun logPlannedShifts(day: LocalDate = LocalDate.now()) = viewModelScope.launch {
        val s = settingsStore.current
        val planned = com.aethernova.nova.data.ShiftPay.planned(day)
        val already = shifts.value.filter { it.day == day.toString() }.map { it.job }.toSet()
        val add = planned.filter { it.job !in already }
        if (add.isEmpty()) { toast.value = if (planned.isEmpty()) "No shifts in your plan today" else "Today's shifts are already logged"; return@launch }
        add.forEach { repo.dao.insertShift(it) }
        toast.value = "Logged: " + add.joinToString { "${com.aethernova.nova.data.ShiftPay.label(it.job)} ${"%.1f".format(it.hours)} h" } +
            if (add.any { it.rate == 0.0 }) " — add hourly rates to your shifts in Settings → My week" else ""
    }
    fun addShift(day: String, job: String, hours: Double, rate: Double, note: String) = viewModelScope.launch {
        val d = day.trim().takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: repo.today()
        if (hours <= 0 || hours > 16) { toast.value = "Hours should be between 0 and 16"; return@launch }
        repo.dao.insertShift(com.aethernova.nova.data.Shift(day = d, job = job, hours = hours, rate = rate.coerceIn(0.0, 100.0), note = note.take(200)))
    }
    fun deleteShift(x: com.aethernova.nova.data.Shift) = viewModelScope.launch { repo.dao.deleteShift(x) }

    // ================= v0.9: kitchen & calm =================
    val cookRecipe = MutableStateFlow<com.aethernova.nova.data.Cooking.Recipe?>(null)
    val cookBusy = MutableStateFlow(false)
    fun aiRecipe(ask: String) = viewModelScope.launch {
        if (ask.isBlank()) return@launch
        cookBusy.value = true
        try { cookRecipe.value = brain.recipe(ask.trim()) }
        catch (e: Exception) { toast.value = if (e is QuotaException || e is NoKeyException) "AI unavailable — pick a built-in recipe" else "Couldn't make that recipe, try again" }
        cookBusy.value = false
    }

    private val mealPrefs = app.getSharedPreferences("nova_mealprep", android.content.Context.MODE_PRIVATE)
    val mealPlan = MutableStateFlow(runCatching {
        mealPrefs.getString("plan", null)?.let { com.aethernova.nova.data.MealPrep.parse(org.json.JSONObject(it)) }
    }.getOrNull())
    val mealBusy = MutableStateFlow(false)
    fun generateMealPrep(note: String, useAi: Boolean = true) = viewModelScope.launch {
        mealBusy.value = true
        val plan = if (!useAi) com.aethernova.nova.data.MealPrep.template() else try { brain.mealPrep(note) } catch (e: Exception) {
            toast.value = "AI unavailable — here's the standard 2-day plan."; com.aethernova.nova.data.MealPrep.template()
        }
        mealPlan.value = plan
        mealPrefs.edit().putString("plan", com.aethernova.nova.data.MealPrep.toJson(plan).toString()).apply()
        mealBusy.value = false
    }
    fun mealPrepToGroceries() = viewModelScope.launch {
        val p = mealPlan.value ?: return@launch
        val have = groceries.value.filter { !it.done }.map { it.name.lowercase() }.toSet()
        val add = p.shopping.filter { it.first.lowercase() !in have }
        repo.dao.insertGrocery(add.map { com.aethernova.nova.data.GroceryItem(name = it.first, qty = it.second) })
        toast.value = "${add.size} items added to your grocery list"
    }
    fun breathingDone(minutes: Int) = viewModelScope.launch {
        if (minutes >= 2) { perform(Step(Mood.SLEEPY, 2500, m("Nice and calm. Phone down, bed soon.", "Sugam aayi. Ini urangaam."), BodyAction.IDLE), Step(Mood.HAPPY, 0)) }
    }

    // Runs last, after every property above is initialised.
    init {
        refreshInsights()
        refreshFootball()
        dailyHello()
    }

    override fun onCleared() { handsFree.stop(); speaker.shutdown() }
}
