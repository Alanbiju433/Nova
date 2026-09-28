package com.aethernova.nova.ai

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class QuotaException(msg: String) : Exception(msg)
/** Server-side hiccup (500/502/503/504) — worth retrying; not the user's fault. */
class TransientException(msg: String) : Exception(msg)

/**
 * Retry policy for Gemini calls: a 503 "model overloaded" is usually gone a second later.
 * primary → wait → primary again → other model. Quota (429) skips straight to the other model.
 * Anything else (no key, bad request) fails immediately so the offline path can take over.
 */
object GeminiRetry {
    fun isTransient(e: Throwable) = e is TransientException || e is java.io.IOException

    suspend fun <T> run(primary: String, other: String, waitMs: Long = 1500, sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
                        call: suspend (String) -> T): T {
        try { return call(primary) } catch (e: QuotaException) {
            if (other == primary) throw e
            return call(other)
        } catch (e: Exception) {
            if (!isTransient(e)) throw e
        }
        sleep(waitMs)
        try { return call(primary) } catch (e: Exception) {
            if (!isTransient(e) && e !is QuotaException || other == primary) throw e
        }
        return call(other)
    }
}
class NoKeyException : Exception("No Gemini API key set")

/** Minimal REST client for the Gemini API (Google AI Studio key). */
class Gemini(private val apiKey: () -> String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Turn(val role: String, val text: String) // role: user | model

    suspend fun generate(
        model: String,
        system: String,
        turns: List<Turn>,
        image: Bitmap? = null,
        json: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isBlank()) throw NoKeyException()

        val contents = JSONArray()
        turns.forEachIndexed { i, t ->
            val parts = JSONArray().put(JSONObject().put("text", t.text))
            if (image != null && i == turns.lastIndex) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", toBase64Jpeg(image))
                    )
                )
            }
            contents.put(JSONObject().put("role", t.role).put("parts", parts))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                if (json) put("responseMimeType", "application/json")
            })

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 429) throw QuotaException("Daily free limit reached for $model")
            if (resp.code in 500..504) throw TransientException("Gemini busy (${resp.code})")
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))
                throw Exception("Gemini ${resp.code}: $msg")
            }
            val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }
        }
    }

    private fun toBase64Jpeg(src: Bitmap): String {
        // Downscale to keep uploads small and quick (~1024 px on the long side).
        val scale = 1024f / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

/** Models sometimes wrap JSON in ```json fences even when asked not to. */
fun String.cleanJson(): String {
    val s = trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = s.indexOfFirst { it == '{' || it == '[' }
    return if (start > 0) s.substring(start) else s
}
