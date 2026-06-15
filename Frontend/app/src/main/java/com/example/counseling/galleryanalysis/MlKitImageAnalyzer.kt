package com.example.counseling.galleryanalysis

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** ML Kit 온디바이스 Image Labeling/Object Detection/Face Detection을 실행한다. */
class MlKitImageAnalyzer(labelConfidenceThreshold: Float = 0.45f) {
    private val imageLabeler: ImageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(labelConfidenceThreshold)
            .build()
    )

    private val objectDetector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableClassification()
            .build()
    )

    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.10f)
            .build()
    )

    suspend fun analyze(bitmap: Bitmap): ImageAnalysisOutput = withContext(Dispatchers.Default) {
        runCatching {
            val input = InputImage.fromBitmap(bitmap, 0)
            val labels = imageLabeler.process(input).await()
                .map { VisionLabel(it.text, it.confidence) }
                .sortedByDescending { it.confidence }

            val objects = objectDetector.process(input).await().flatMap { detected ->
                if (detected.labels.isNotEmpty()) {
                    detected.labels.map { label -> VisionObject(label.text, label.confidence) }
                } else {
                    listOf(VisionObject("Object", 0.20f))
                }
            }.sortedByDescending { it.confidence }

            val faceCount = faceDetector.process(input).await().size
            ImageAnalysisOutput(
                labels = labels,
                objects = objects,
                faceCount = faceCount,
                socialContextType = inferSocialContext(labels, objects, faceCount),
                mlKitSucceeded = true
            )
        }.getOrElse { ImageAnalysisOutput.failed(it.message) }
    }

    private fun inferSocialContext(
        labels: List<VisionLabel>,
        objects: List<VisionObject>,
        faceCount: Int
    ): SocialContextType {
        if (faceCount >= 2) return SocialContextType.MULTI_FACE
        val text = (labels.map { it.text } + objects.map { it.category }).joinToString(" ").lowercase()
        if (listOf("crowd", "group", "people", "party", "audience").any { text.contains(it) }) {
            return SocialContextType.PEOPLE_OR_CROWD_LABEL
        }
        if (listOf("restaurant", "cafe", "festival", "stadium", "classroom").any { text.contains(it) }) {
            return SocialContextType.PUBLIC_PLACE_LABEL
        }
        if (faceCount == 1) return SocialContextType.SINGLE_FACE
        return SocialContextType.NONE
    }

    fun close() {
        imageLabeler.close()
        objectDetector.close()
        faceDetector.close()
    }
}
