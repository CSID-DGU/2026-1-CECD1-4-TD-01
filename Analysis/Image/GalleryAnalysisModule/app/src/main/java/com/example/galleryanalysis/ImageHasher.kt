package com.example.galleryanalysis

import android.graphics.Bitmap
import android.graphics.Color

/** 8x8 average hash. 연사/중복 사진 묶기 보조용이며 원본은 저장하지 않는다. */
object ImageHasher {
    fun averageHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        try {
            val grays = IntArray(64)
            var sum = 0
            var index = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val c = small.getPixel(x, y)
                    val gray = ((Color.red(c) * 0.299) + (Color.green(c) * 0.587) + (Color.blue(c) * 0.114)).toInt()
                    grays[index++] = gray
                    sum += gray
                }
            }
            val avg = sum / 64
            var hash = 0L
            grays.forEachIndexed { i, value -> if (value >= avg) hash = hash or (1L shl i) }
            return hash
        } finally {
            if (small !== bitmap && !small.isRecycled) small.recycle()
        }
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
