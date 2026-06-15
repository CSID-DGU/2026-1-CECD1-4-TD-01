package com.example.counseling.galleryanalysis

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 최근 N개월 MediaStore 이미지 메타데이터를 수집한다. */
class GalleryScanner(private val context: Context) {

    @Suppress("DEPRECATION")
    suspend fun scanRecentImages(months: Int = 6): List<GalleryImage> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cutoffMillis = Instant.now().minus(months.toLong() * 31L, ChronoUnit.DAYS).toEpochMilli()
        val cutoffSeconds = cutoffMillis / 1000L

        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.MIME_TYPE)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.WIDTH)
            add(MediaStore.Images.Media.HEIGHT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                add(MediaStore.Images.Media.DATA)
            }
        }.toTypedArray()

        val selection = "(${MediaStore.Images.Media.DATE_TAKEN} >= ? OR ${MediaStore.Images.Media.DATE_ADDED} >= ?)"
        val selectionArgs = arrayOf(cutoffMillis.toString(), cutoffSeconds.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val results = mutableListOf<GalleryImage>()
        resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val dateTaken = cursor.getLongOrNull(takenCol)?.takeIf { it > 0L }
                val dateAdded = cursor.getLongOrNull(addedCol)?.takeIf { it > 0L }?.let { it * 1000L }
                val fileName = cursor.getStringOrNull(nameCol)
                val path = cursor.getStringOrNull(pathCol)?.let { raw ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) raw else raw.substringBeforeLast('/', missingDelimiterValue = "")
                }
                val uri = ContentUris.withAppendedId(collection, id)
                val inferredTakenAt = inferTakenAtMillis(uri, fileName)
                results += GalleryImage(
                    imageId = id,
                    uri = uri,
                    fileName = fileName,
                    relativePath = path,
                    mimeType = cursor.getStringOrNull(mimeCol),
                    dateTakenMillis = dateTaken,
                    dateAddedMillis = dateAdded,
                    inferredTakenAtMillis = inferredTakenAt,
                    width = cursor.getIntOrNull(widthCol),
                    height = cursor.getIntOrNull(heightCol)
                )
            }
        }
        results
    }

    private fun inferTakenAtMillis(uri: android.net.Uri, fileName: String?): Long? {
        return readExifDateTimeMillis(uri) ?: parseCameraFileNameDateMillis(fileName)
    }

    private fun readExifDateTimeMillis(uri: android.net.Uri): Long? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                parseExifDateMillis(raw)
            }
        }.getOrNull()
    }

    private fun parseExifDateMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val formatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)
            LocalDateTime.parse(raw.trim(), formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun parseCameraFileNameDateMillis(fileName: String?): Long? {
        if (fileName.isNullOrBlank()) return null
        val match = Regex("""(?:IMG|VID|PXL|Screenshot|KakaoTalk)[_-]?(\d{8})[_-]?(\d{6})""", RegexOption.IGNORE_CASE)
            .find(fileName) ?: return null
        val compact = match.groupValues[1] + match.groupValues[2]
        return runCatching {
            val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US)
            LocalDateTime.parse(compact, formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

}

private fun android.database.Cursor.getStringOrNull(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)

private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
    if (index < 0 || isNull(index)) null else getLong(index)

private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
    if (index < 0 || isNull(index)) null else getInt(index)
