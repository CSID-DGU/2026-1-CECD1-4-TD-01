package com.example.counseling.galleryanalysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** 원본을 저장하지 않고 분석용 축소 Bitmap만 메모리에 생성한다. */
class ThumbnailLoader(private val context: Context) {

    suspend fun loadThumbnail(uri: Uri, targetLongSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetLongSide)
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } ?: return@withContext null

        val orientation = resolver.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val rotated = applyExifOrientation(decoded, orientation)
        if (rotated !== decoded && !decoded.isRecycled) decoded.recycle()
        val scaled = scaleDownIfNeeded(rotated, targetLongSide)
        if (scaled !== rotated && !rotated.isRecycled) rotated.recycle()
        scaled
    }

    private fun calculateInSampleSize(width: Int, height: Int, targetLongSide: Int): Int {
        var sample = 1
        val longSide = max(width, height)
        while ((longSide / sample) > targetLongSide * 2) sample *= 2
        return sample.coerceAtLeast(1)
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, targetLongSide: Int): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide <= targetLongSide) return bitmap
        val ratio = targetLongSide.toFloat() / longSide.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}
