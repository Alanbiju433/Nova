package com.aethernova.nova.vision

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * The pose and face models (~6 MB and ~4 MB) are downloaded once on first use instead of
 * being baked into the APK, which keeps updates small. Stored in app-private storage.
 */
object ModelStore {
    enum class Model(val file: String, val url: String) {
        POSE("pose_landmarker_lite.task",
            "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task"),
        FACE("face_landmarker.task",
            "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task"),
    }

    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    private fun file(ctx: Context, m: Model) = File(File(ctx.filesDir, "models").apply { mkdirs() }, m.file)

    fun isReady(ctx: Context, m: Model) = file(ctx, m).let { it.exists() && it.length() > 100_000 }

    /** Downloads if needed; returns the model as a direct ByteBuffer for MediaPipe. */
    suspend fun load(ctx: Context, m: Model, onProgress: (Int) -> Unit = {}): ByteBuffer = withContext(Dispatchers.IO) {
        val f = file(ctx, m)
        if (!isReady(ctx, m)) {
            val tmp = File(f.parentFile, f.name + ".part")
            http.newCall(Request.Builder().url(m.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Model download failed (${resp.code})")
                val body = resp.body!!
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var read = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); read += n
                            if (total > 0) onProgress((read * 100 / total).toInt())
                        }
                    }
                }
            }
            tmp.renameTo(f)
        }
        val bytes = f.readBytes()
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    }
}
