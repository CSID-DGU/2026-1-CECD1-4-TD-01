package com.example.counseling.galleryanalysis

import kotlin.math.max

/** 스크린샷/다운로드/메신저/편집 이미지처럼 실제 활동 기록 오염 가능성이 큰 항목을 제외한다. */
object ImageFilter {
    private val screenshotWords = listOf("screenshot", "screenshots", "screen_shot", "스크린샷", "화면캡처", "화면 캡처")
    private val downloadWords = listOf("download", "downloads", "downloaded_image", "naver", "googleimages", "browser")
    private val messengerWords = listOf("kakaotalk", "kakao", "messenger", "whatsapp", "telegram", "line", "discord", "instagram", "dm")
    private val editedWords = listOf("edited", "edit", "crop", "cropped", "retouch", "filter", "beauty", "snapseed", "remix", "collage")

    fun filter(image: GalleryImage): FilterResult {
        val name = image.fileName.orEmpty().lowercase()
        val path = image.relativePath.orEmpty().lowercase()
        val joined = "$path/$name"

        if (screenshotWords.any { joined.contains(it) }) {
            return FilterResult(ImageFilterStatus.EXCLUDED_SCREENSHOT, "screenshot-like filename/folder")
        }
        if (downloadWords.any { joined.contains(it) }) {
            return FilterResult(ImageFilterStatus.EXCLUDED_DOWNLOAD, "download-like filename/folder")
        }
        if (messengerWords.any { joined.contains(it) }) {
            return FilterResult(ImageFilterStatus.EXCLUDED_MESSENGER, "messenger/cache-like filename/folder")
        }
        if (editedWords.any { name.contains(it) }) {
            return FilterResult(ImageFilterStatus.EXCLUDED_EDITED, "edited/cropped/filtered filename")
        }

        val width = image.width ?: 0
        val height = image.height ?: 0
        val longSide = max(width, height)
        val area = width * height
        if (width > 0 && height > 0 && (longSide < 320 || area < 100_000)) {
            return FilterResult(ImageFilterStatus.EXCLUDED_LOW_QUALITY, "too small for reliable visual analysis")
        }

        val mime = image.mimeType.orEmpty().lowercase()
        if (mime.isNotBlank() && !mime.startsWith("image/")) {
            return FilterResult(ImageFilterStatus.UNKNOWN, "not an image mime type")
        }

        return FilterResult(ImageFilterStatus.ANALYZABLE)
    }
}
