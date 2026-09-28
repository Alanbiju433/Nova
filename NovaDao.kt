package com.aethernova.nova.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NovaDao {
    // Food
    @Insert suspend fun insertFood(entry: FoodEntry): Long
    @Delete suspend fun deleteFood(entry: FoodEntry)
    @Query("SELECT * FROM food WHERE day = :day ORDER BY timestamp")
    fun foodForDay(day: String): Flow<List<FoodEntry>>
    @Query("SELECT * FROM food WHERE day = :day ORDER BY timestamp")
    suspend fun foodForDayNow(day: String): List<FoodEntry>
    @Query("SELECT day, CAST(SUM(kcal) AS INTEGER) AS kcal, SUM(proteinG) AS protein FROM food WHERE day >= :fromDay GROUP BY day ORDER BY day")
    suspend fun dailyTotalsSince(fromDay: String): List<DayTotals>

    // Check-ins
    @Insert suspend fun insertCheckIn(c: CheckIn): Long
    @Query("SELECT * FROM checkin ORDER BY timestamp DESC LIMIT :n")
    suspend fun recentCheckIns(n: Int): List<CheckIn>
    @Query("SELECT * FROM checkin WHERE weightKg IS NOT NULL ORDER BY timestamp")
    fun weights(): Flow<List<CheckIn>>
    @Query("SELECT * FROM checkin WHERE day = :day ORDER BY timestamp DESC LIMIT 1")
    fun latestCheckInForDay(day: String): Flow<CheckIn?>

    // Workouts
    @Insert suspend fun insertWorkout(w: WorkoutLog): Long
    @Delete suspend fun deleteWorkout(w: WorkoutLog)
    @Query("SELECT * FROM workout WHERE day >= :fromDay ORDER BY timestamp DESC")
    fun workoutsSince(fromDay: String): Flow<List<WorkoutLog>>
    @Query("SELECT * FROM workout WHERE day >= :fromDay ORDER BY timestamp DESC")
    suspend fun workoutsSinceNow(fromDay: String): List<WorkoutLog>

    // Health results
    @Insert suspend fun insertResult(r: HealthResult): Long
    @Delete suspend fun deleteResult(r: HealthResult)
    @Query("SELECT * FROM health_result ORDER BY marker, day")
    fun results(): Flow<List<HealthResult>>
    @Query("SELECT * FROM health_result ORDER BY day DESC")
    suspend fun resultsNow(): List<HealthResult>
    @Query("SELECT COUNT(*) FROM health_result")
    suspend fun resultCount(): Int

    // Chat
    @Insert suspend fun insertChat(m: ChatMessage): Long
    @Query("SELECT * FROM chat ORDER BY timestamp DESC LIMIT :n")
    fun recentChat(n: Int): Flow<List<ChatMessage>>
    @Query("SELECT * FROM chat ORDER BY timestamp DESC LIMIT :n")
    suspend fun recentChatNow(n: Int): List<ChatMessage>
    @Query("DELETE FROM chat")
    suspend fun clearChat()

    // ---------- v2 ----------
    @Insert suspend fun insertXp(e: XpEvent): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM xp_event")
    fun totalXp(): Flow<Int>
    @Query("SELECT COALESCE(SUM(amount), 0) FROM xp_event")
    suspend fun totalXpNow(): Int
    @Query("SELECT COALESCE(SUM(amount), 0) FROM xp_event WHERE day = :day AND kind = :kind")
    suspend fun xpForDayKind(day: String, kind: String): Int
    @Query("SELECT day, CAST(SUM(amount) AS INTEGER) AS xp FROM xp_event GROUP BY day ORDER BY day DESC LIMIT 400")
    suspend fun xpByDay(): List<DayXp>
    @Query("SELECT day, CAST(SUM(amount) AS INTEGER) AS xp FROM xp_event GROUP BY day ORDER BY day DESC LIMIT 400")
    fun xpByDayFlow(): Flow<List<DayXp>>

    @Insert suspend fun insertWater(w: WaterLog): Long
    @Delete suspend fun deleteWater(w: WaterLog)
    @Query("SELECT COALESCE(SUM(ml), 0) FROM water WHERE day = :day")
    fun waterForDay(day: String): Flow<Int>
    @Query("SELECT COALESCE(SUM(ml), 0) FROM water WHERE day = :day")
    suspend fun waterForDayNow(day: String): Int
    @Query("SELECT day, CAST(SUM(ml) AS INTEGER) AS ml FROM water WHERE day >= :fromDay GROUP BY day")
    suspend fun waterSince(fromDay: String): List<DayWater>
    @Query("SELECT * FROM water WHERE day = :day ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastWater(day: String): WaterLog?

    @Insert suspend fun insertFocus(f: FocusSession): Long
    @Query("SELECT * FROM focus_session WHERE day >= :fromDay ORDER BY timestamp DESC")
    fun focusSince(fromDay: String): Flow<List<FocusSession>>
    @Query("SELECT * FROM focus_session WHERE day >= :fromDay ORDER BY timestamp DESC")
    suspend fun focusSinceNow(fromDay: String): List<FocusSession>

    @Query("SELECT MAX(timestamp) FROM food")
    fun lastFoodTime(): Flow<Long?>
    @Query("SELECT * FROM checkin WHERE timestamp >= :since ORDER BY timestamp")
    suspend fun checkInsSince(since: Long): List<CheckIn>
    @Query("SELECT * FROM checkin WHERE mood IS NOT NULL ORDER BY timestamp DESC LIMIT :n")
    fun moodEntries(n: Int): Flow<List<CheckIn>>

    // ---------- v3 ----------
    @Insert suspend fun insertGrocery(items: List<GroceryItem>)
    @androidx.room.Update suspend fun updateGrocery(item: GroceryItem)
    @Delete suspend fun deleteGrocery(item: GroceryItem)
    @Query("DELETE FROM grocery_item WHERE done = 1")
    suspend fun clearDoneGrocery()
    @Query("SELECT * FROM grocery_item ORDER BY done, createdAt")
    fun groceries(): Flow<List<GroceryItem>>

    @Insert suspend fun insertPhoto(p: ProgressPhoto): Long
    @Delete suspend fun deletePhoto(p: ProgressPhoto)
    @Query("SELECT * FROM progress_photo ORDER BY timestamp")
    fun photos(): Flow<List<ProgressPhoto>>

    // ---------- v4 ----------
    @Insert suspend fun insertExpense(e: Expense): Long
    @Delete suspend fun deleteExpense(e: Expense)
    @Query("SELECT * FROM expense WHERE day >= :fromDay ORDER BY timestamp DESC")
    fun expensesSince(fromDay: String): Flow<List<Expense>>
    @Query("SELECT * FROM expense WHERE day >= :fromDay ORDER BY timestamp DESC")
    suspend fun expensesSinceNow(fromDay: String): List<Expense>

    @Insert suspend fun insertFact(f: MemoryFact): Long
    @Delete suspend fun deleteFact(f: MemoryFact)
    @Query("SELECT * FROM memory_fact ORDER BY createdAt DESC")
    fun facts(): Flow<List<MemoryFact>>
    @Query("SELECT * FROM memory_fact ORDER BY createdAt DESC LIMIT 60")
    suspend fun factsNow(): List<MemoryFact>

    // ---------- v5 ----------
    @Insert suspend fun insertMed(m: Med): Long
    @androidx.room.Update suspend fun updateMed(m: Med)
    @Query("SELECT * FROM med ORDER BY active DESC, name")
    fun meds(): Flow<List<Med>>
    @Query("SELECT * FROM med WHERE active = 1")
    suspend fun activeMedsNow(): List<Med>
    @Query("SELECT * FROM med WHERE id = :id")
    suspend fun med(id: Long): Med?

    @Insert suspend fun insertMedLog(l: MedLog): Long
    @Delete suspend fun deleteMedLog(l: MedLog)
    @Query("SELECT * FROM med_log WHERE day >= :fromDay")
    fun medLogsSince(fromDay: String): Flow<List<MedLog>>
    @Query("SELECT * FROM med_log WHERE day >= :fromDay")
    suspend fun medLogsSinceNow(fromDay: String): List<MedLog>

    @Insert suspend fun insertFollowup(f: Followup): Long
    @androidx.room.Update suspend fun updateFollowup(f: Followup)
    @Delete suspend fun deleteFollowup(f: Followup)
    @Query("SELECT * FROM followup ORDER BY done, dueDay")
    fun followups(): Flow<List<Followup>>
    @Query("SELECT * FROM followup WHERE done = 0")
    suspend fun openFollowupsNow(): List<Followup>
    @Query("SELECT COUNT(*) FROM followup")
    suspend fun followupCount(): Int

    // for the doctor summary / backup
    @Query("SELECT * FROM checkin WHERE timestamp >= :since ORDER BY timestamp")
    suspend fun checkInsFrom(since: Long): List<CheckIn>
    @Query("SELECT * FROM health_result ORDER BY marker, day")
    suspend fun resultsSorted(): List<HealthResult>
    @Query("SELECT * FROM progress_photo")
    suspend fun photosNow(): List<ProgressPhoto>
    @androidx.room.RawQuery
    suspend fun checkpoint(q: androidx.sqlite.db.SupportSQLiteQuery): Int

    // ---------- v6 ----------
    @Insert suspend fun insertGymSet(s: GymSet): Long
    @Delete suspend fun deleteGymSet(s: GymSet)
    @Query("SELECT * FROM gym_set WHERE day >= :fromDay ORDER BY timestamp")
    fun gymSetsSince(fromDay: String): Flow<List<GymSet>>
    @Query("SELECT * FROM gym_set WHERE exercise = :exercise ORDER BY timestamp")
    suspend fun gymHistory(exercise: String): List<GymSet>
    @Query("SELECT * FROM food WHERE day >= :fromDay ORDER BY timestamp")
    suspend fun foodSince(fromDay: String): List<FoodEntry>
    @Query("SELECT * FROM checkin WHERE day >= :fromDay ORDER BY timestamp")
    suspend fun checkInsSinceDay(fromDay: String): List<CheckIn>

    // ---------- v7 ----------
    @Insert suspend fun insertJob(j: JobApp): Long
    @androidx.room.Update suspend fun updateJob(j: JobApp)
    @Delete suspend fun deleteJob(j: JobApp)
    @Query("SELECT * FROM job_app ORDER BY CASE status WHEN 'interview' THEN 0 WHEN 'offer' THEN 1 WHEN 'applied' THEN 2 WHEN 'saved' THEN 3 ELSE 4 END, deadline")
    fun jobs(): Flow<List<JobApp>>
    @Query("SELECT * FROM job_app WHERE deadline != '' AND status IN ('saved','applied','interview')")
    suspend fun jobsWithDeadlinesNow(): List<JobApp>

    @Insert suspend fun insertMilestone(m: DissMilestone): Long
    @androidx.room.Update suspend fun updateMilestone(m: DissMilestone)
    @Delete suspend fun deleteMilestone(m: DissMilestone)
    @Query("SELECT * FROM diss_milestone ORDER BY dueDay")
    fun milestones(): Flow<List<DissMilestone>>
    @Query("SELECT COUNT(*) FROM diss_milestone")
    suspend fun milestoneCount(): Int
    @Query("SELECT * FROM diss_milestone WHERE done = 0 ORDER BY dueDay LIMIT 1")
    suspend fun nextMilestone(): DissMilestone?

    @Insert suspend fun insertDissLog(l: DissLog): Long
    @Delete suspend fun deleteDissLog(l: DissLog)
    @Query("SELECT * FROM diss_log ORDER BY day, id")
    fun dissLogs(): Flow<List<DissLog>>

    @Insert suspend fun insertCards(c: List<Flashcard>)
    @androidx.room.Update suspend fun updateCard(c: Flashcard)
    @Delete suspend fun deleteCard(c: Flashcard)
    @Query("SELECT * FROM flashcard ORDER BY deck, id")
    fun cards(): Flow<List<Flashcard>>

    @Insert suspend fun insertShift(s: Shift): Long
    @Delete suspend fun deleteShift(s: Shift)
    @Query("SELECT * FROM shift WHERE day >= :fromDay ORDER BY day DESC")
    fun shiftsSince(fromDay: String): Flow<List<Shift>>
}
