package com.aethernova.nova.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aethernova.nova.CrashLog
import java.util.concurrent.Executors

/** Anything that wants upright camera frames (gestures, pose, face). */
interface FrameConsumer {
    fun process(frame: Bitmap, timestampMs: Long)
    fun close()
}

/** Runs several consumers, taking turns frame by frame so each gets ~half the frame rate. */
class TakeTurns(private val consumers: List<FrameConsumer>) : FrameConsumer {
    private var i = 0
    override fun process(frame: Bitmap, timestampMs: Long) {
        if (consumers.isEmpty()) return
        consumers[i % consumers.size].process(frame, timestampMs); i++
    }
    override fun close() = consumers.forEach { runCatching { it.close() } }
}

private class UprightAnalyzer(private val consumer: FrameConsumer, private val mirror: Boolean, private val minGapMs: Long) : ImageAnalysis.Analyzer {
    private var last = 0L
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        if (now - last < minGapMs) { image.close(); return }
        last = now
        try {
            val bmp = image.toBitmap()
            val m = Matrix().apply {
                postRotate(image.imageInfo.rotationDegrees.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            consumer.process(Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true), now)
        } catch (_: Throwable) {
            // drop the frame
        } finally {
            image.close()
        }
    }
}

/**
 * Front camera with a small preview. [create] builds the consumer on a background thread
 * (loading ML models can take a second). Errors are reported instead of crashing the app.
 */
@Composable
fun LiveCamera(
    visible: Boolean,
    modifier: Modifier,
    minGapMs: Long = 80,
    create: (Context) -> FrameConsumer,
    onError: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val main = ContextCompat.getMainExecutor(ctx)

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        var consumer: FrameConsumer? = null
        var disposed = false
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        CrashLog.markCameraStarting(ctx, true)
        executor.execute {
            val c = try { create(ctx) } catch (t: Throwable) {
                main.execute { onError("Couldn't start: ${t.javaClass.simpleName} ${t.message?.take(80) ?: ""}") }
                CrashLog.markCameraStarting(ctx, false)
                return@execute
            }
            consumer = c
            providerFuture.addListener({
                if (disposed) return@addListener
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build().also { it.setAnalyzer(executor, UprightAnalyzer(c, mirror = true, minGapMs = minGapMs)) }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                    previewView.postDelayed({ CrashLog.markCameraStarting(ctx, false) }, 4000)
                } catch (t: Throwable) {
                    onError("Camera error: ${t.javaClass.simpleName} ${t.message?.take(80) ?: ""}")
                    CrashLog.markCameraStarting(ctx, false)
                }
            }, main)
        }
        onDispose {
            disposed = true
            runCatching { providerFuture.get().unbindAll() }
            executor.execute { runCatching { consumer?.close() } }
            executor.shutdown()
        }
    }
    AndroidView({ previewView }, modifier.then(if (visible) Modifier else Modifier.size(1.dp)))
}
