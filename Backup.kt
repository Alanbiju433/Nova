package com.aethernova.nova.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.aethernova.nova.MainActivity
import com.aethernova.nova.NovaApp
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password encryption for backup files: PBKDF2-SHA256 (150k rounds) -> AES-256-GCM. Pure JVM, unit-tested. */
object BackupCrypto {
    private val MAGIC = "NOVABK1".toByteArray()
    private const val ROUNDS = 150_000

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(PBEKeySpec(password, salt, ROUNDS, 256)).encoded, "AES")
    }

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password.toCharArray(), salt), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC)
        return MAGIC + salt + iv + c.doFinal(plain)
    }

    /** Throws [WrongPassword] if the password is wrong or the file was changed. */
    fun decrypt(blob: ByteArray, password: String): ByteArray {
        if (blob.size < MAGIC.size + 28 || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw NotABackup()
        val salt = blob.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = blob.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password.toCharArray(), salt), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC)
        return try { c.doFinal(blob, MAGIC.size + 28, blob.size - MAGIC.size - 28) } catch (e: Exception) { throw WrongPassword() }
    }

    class WrongPassword : Exception("Wrong password (or the file is damaged)")
    class NotABackup : Exception("That file isn't a Nova backup")

    fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> entries.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
    }.toByteArray()

    fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && !e.name.contains("..")) out[e.name] = z.readBytes()
            }
        }
        return out
    }
}

/**
 * Everything Nova knows, in one password-protected file you can keep in Google Drive:
 * the encrypted database + its key, settings, and progress photos. API keys are NOT included —
 * paste them again after restoring.
 */
object Backup {
    private const val SETTINGS = "nova_settings"

    private fun prefsToJson(ctx: Context, name: String): String {
        val o = JSONObject()
        ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val t = when (v) { is String -> "s"; is Int -> "i"; is Boolean -> "b"; is Long -> "l"; is Float -> "f"; else -> null } ?: return@forEach
            o.put(k, JSONObject().put("t", t).put("v", v))
        }
        return o.toString()
    }

    private fun jsonToPrefs(ctx: Context, name: String, json: String) {
        val o = JSONObject(json)
        val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        o.keys().forEach { k ->
            val x = o.getJSONObject(k)
            when (x.getString("t")) {
                "s" -> e.putString(k, x.getString("v")); "i" -> e.putInt(k, x.getInt("v")); "b" -> e.putBoolean(k, x.getBoolean("v"))
                "l" -> e.putLong(k, x.getLong("v")); "f" -> e.putFloat(k, x.getDouble("v").toFloat())
            }
        }
        e.commit()
    }

    fun export(ctx: Context, target: Uri, password: String): Int {
        val app = ctx.applicationContext as NovaApp
        if (app.demo) throw IllegalStateException("Switch off demo mode first — backups are for your real data.")
        runCatching { app.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() } }
        val entries = linkedMapOf<String, ByteArray>()
        entries["nova.db"] = ctx.getDatabasePath(NovaDatabase.NAME).readBytes()
        entries["db_pass.txt"] = Base64.encodeToString(Secure.databasePassphrase(ctx), Base64.NO_WRAP).toByteArray()
        entries["settings.json"] = prefsToJson(ctx, SETTINGS).toByteArray()
        entries["games.json"] = prefsToJson(ctx, "nova_games").toByteArray()
        entries["mealprep.json"] = prefsToJson(ctx, "nova_mealprep").toByteArray()
        File(ctx.filesDir, "progress").listFiles()?.forEach { entries["progress/${it.name}"] = it.readBytes() }
        val blob = BackupCrypto.encrypt(BackupCrypto.zip(entries), password)
        ctx.contentResolver.openOutputStream(target, "w")!!.use { it.write(blob) }
        ctx.getSharedPreferences("nova_backup", Context.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply()
        return blob.size
    }

    fun lastBackup(ctx: Context): Long = ctx.getSharedPreferences("nova_backup", Context.MODE_PRIVATE).getLong("last", 0)

    /** Replaces all current data with the backup, then restarts Nova. */
    fun restore(ctx: Context, source: Uri, password: String) {
        if ((ctx.applicationContext as NovaApp).demo) throw IllegalStateException("Switch off demo mode first.")
        val blob = ctx.contentResolver.openInputStream(source)!!.use { it.readBytes() }
        val files = BackupCrypto.unzip(BackupCrypto.decrypt(blob, password))
        val db = files["nova.db"] ?: throw BackupCrypto.NotABackup()
        val pass = files["db_pass.txt"]?.let { String(it) } ?: throw BackupCrypto.NotABackup()

        val app = ctx.applicationContext as NovaApp
        runCatching { app.db.close() }
        val path = ctx.getDatabasePath(NovaDatabase.NAME)
        listOf(path, File(path.path + "-wal"), File(path.path + "-shm")).forEach { it.delete() }
        path.parentFile?.mkdirs()
        path.writeBytes(db)
        Secure.put(ctx, "db_pass", pass)
        files["settings.json"]?.let { jsonToPrefs(ctx, SETTINGS, String(it)) }
        files["games.json"]?.let { jsonToPrefs(ctx, "nova_games", String(it)) }
        files["mealprep.json"]?.let { jsonToPrefs(ctx, "nova_mealprep", String(it)) }
        val dir = File(ctx.filesDir, "progress").apply { mkdirs() }
        files.filterKeys { it.startsWith("progress/") }.forEach { (k, v) -> File(dir, k.removePrefix("progress/")).writeBytes(v) }

        // restart cleanly so everything reopens with the restored data
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }
}
