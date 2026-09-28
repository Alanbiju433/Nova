package com.aethernova.nova.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.nio.ByteBuffer

/** Body pose (33 landmarks) for the camera coach. Model buffer comes from [ModelStore]. */
class PoseEngine(context: Context, model: ByteBuffer, private val onPose: (List<P>?) -> Unit) : FrameConsumer {
    private val landmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setResultListener { result, _ ->
                runCatching {
                    val lm = result.landmarks().firstOrNull()
                    onPose(lm?.map { P(it.x(), it.y(), it.visibility().orElse(1f)) })
                }
            }
            .setErrorListener { }
            .build()
    )

    override fun process(frame: Bitmap, timestampMs: Long) = landmarker.detectAsync(BitmapImageBuilder(frame).build(), timestampMs)
    override fun close() = landmarker.close()
}

/** What Nova reads from your face. */
enum class FaceSignal { SMILE, EYES_CLOSED, SURPRISED, NONE }

object FaceRules {
    /** Blendshape scores (0..1) by name -> the strongest signal. */
    fun classify(b: Map<String, Float>): FaceSignal {
        val smile = ((b["mouthSmileLeft"] ?: 0f) + (b["mouthSmileRight"] ?: 0f)) / 2
        val blink = ((b["eyeBlinkLeft"] ?: 0f) + (b["eyeBlinkRight"] ?: 0f)) / 2
        val jaw = b["jawOpen"] ?: 0f
        val brow = b["browInnerUp"] ?: 0f
        return when {
            jaw > 0.45f && brow > 0.35f -> FaceSignal.SURPRISED
            smile > 0.55f -> FaceSignal.SMILE
            blink > 0.6f -> FaceSignal.EYES_CLOSED
            else -> FaceSignal.NONE
        }
    }
}

/**
 * Face blendshapes -> smile / eyes closed (tired) / surprised. A signal must hold for a while
 * before it's reported: smile 0.8 s, surprise 0.4 s, eyes closed 2 s (so normal blinks don't count).
 */
class FaceEngine(context: Context, model: ByteBuffer, private val onSignal: (FaceSignal) -> Unit) : FrameConsumer {
    private var current = FaceSignal.NONE
    private var since = 0L
    private var reported = false

    private val landmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setResultListener { result, _ ->
                runCatching {
                    val shapes = result.faceBlendshapes().orElse(null)?.firstOrNull()
                    val map = shapes?.associate { it.categoryName() to it.score() } ?: emptyMap()
                    update(FaceRules.classify(map), result.timestampMs())
                }
            }
            .setErrorListener { }
            .build()
    )

    private fun update(sig: FaceSignal, now: Long) {
        if (sig != current) { current = sig; since = now; reported = false; return }
        val hold = when (sig) { FaceSignal.SMILE -> 800; FaceSignal.SURPRISED -> 400; FaceSignal.EYES_CLOSED -> 2000; FaceSignal.NONE -> Long.MAX_VALUE.toInt() }
        if (!reported && now - since >= hold) { reported = true; onSignal(sig) }
    }

    override fun process(frame: Bitmap, timestampMs: Long) = landmarker.detectAsync(BitmapImageBuilder(frame).build(), timestampMs)
    override fun close() = landmarker.close()
}
