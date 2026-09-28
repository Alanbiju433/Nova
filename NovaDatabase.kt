package com.aethernova.nova.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [FoodEntry::class, CheckIn::class, WorkoutLog::class, HealthResult::class, ChatMessage::class,
        XpEvent::class, WaterLog::class, FocusSession::class, GroceryItem::class, ProgressPhoto::class, Expense::class, MemoryFact::class,
        Med::class, MedLog::class, Followup::class, GymSet::class,
        JobApp::class, DissMilestone::class, DissLog::class, Flashcard::class, Shift::class],
    version = 7,
    exportSchema = true,
)
abstract class NovaDatabase : RoomDatabase() {
    abstract fun dao(): NovaDao

    companion object {
        /** v0.2 -> v0.3: adds XP, water and focus tables. Existing data is untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `xp_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `day` TEXT NOT NULL, `kind` TEXT NOT NULL, `amount` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `water` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `day` TEXT NOT NULL, `ml` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `focus_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `day` TEXT NOT NULL, `minutes` INTEGER NOT NULL, `label` TEXT NOT NULL)")
            }
        }

        /** v0.3 -> v0.4: grocery list and private progress photos. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `grocery_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `qty` TEXT NOT NULL, `done` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `progress_photo` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `day` TEXT NOT NULL, `path` TEXT NOT NULL, `weightKg` REAL)")
            }
        }

        /** v0.4 -> v0.5: budget expenses and Nova's personal memory. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `expense` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `day` TEXT NOT NULL, `amount` REAL NOT NULL, `category` TEXT NOT NULL, `note` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `memory_fact` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            }
        }

        /** v0.6 -> v0.7: supplements/meds, dose log and follow-up tests. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `med` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `dose` TEXT NOT NULL, `times` TEXT NOT NULL, `weekday` INTEGER NOT NULL, `startDay` TEXT NOT NULL, `endDay` TEXT NOT NULL, `active` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `med_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `medId` INTEGER NOT NULL, `day` TEXT NOT NULL, `slot` TEXT NOT NULL, `takenAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `followup` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `dueDay` TEXT NOT NULL, `note` TEXT NOT NULL, `done` INTEGER NOT NULL)")
            }
        }

        /** v0.7 -> v0.8: liver score on each food entry, and the gym log. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `food` ADD COLUMN `liver` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS `gym_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `day` TEXT NOT NULL, `exercise` TEXT NOT NULL, `weightKg` REAL NOT NULL, `reps` INTEGER NOT NULL)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `job_app` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `company` TEXT NOT NULL, `role` TEXT NOT NULL, `status` TEXT NOT NULL, `deadline` TEXT NOT NULL, `link` TEXT NOT NULL, `notes` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `diss_milestone` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `dueDay` TEXT NOT NULL, `done` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `diss_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` TEXT NOT NULL, `kind` TEXT NOT NULL, `words` INTEGER NOT NULL, `text` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `flashcard` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `deck` TEXT NOT NULL, `front` TEXT NOT NULL, `back` TEXT NOT NULL, `box` INTEGER NOT NULL, `dueDay` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `shift` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` TEXT NOT NULL, `job` TEXT NOT NULL, `hours` REAL NOT NULL, `rate` REAL NOT NULL, `note` TEXT NOT NULL)")
            }
        }
        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

        const val NAME = "nova.db"

        fun create(context: Context, name: String = NAME): NovaDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(Secure.databasePassphrase(context))
            return Room.databaseBuilder(context, NovaDatabase::class.java, name)
                .openHelperFactory(factory)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
        }
    }
}
