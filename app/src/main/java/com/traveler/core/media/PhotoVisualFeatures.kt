package com.traveler.core.media

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class PhotoVisualFeatures(val hash: Long, val sharpness: Float, val exposure: Float,
    val color: List<Float>, val category: String = "other", val labels: List<String> = emptyList(), val labelsComputed: Boolean = false) {
    val qualityScore: Int get() = (min(1f, sharpness / 900f)*240 + exposure*120).toInt()
    fun similar(other: PhotoVisualFeatures): Boolean = java.lang.Long.bitCount(hash xor other.hash) <= 6 &&
        color.zip(other.color).sumOf { (a,b) -> abs(a-b).toDouble() } < .22
}

/** Bitmap-independent features for reproducible selection and synthetic image tests. */
object PhotoVisualMath {
    fun extract(pixels: IntArray, width: Int, height: Int): PhotoVisualFeatures {
        require(width >= 9 && height >= 8 && pixels.size == width*height)
        fun gray(x: Int,y: Int): Double {
            val p=pixels[y*width+x]
            return ((p shr 16 and 255)*.299+(p shr 8 and 255)*.587+(p and 255)*.114)
        }
        var hash=0L
        for(y in 0..7) for(x in 0..7) if(gray(x*width/9,y*height/8)>gray((x+1)*width/9,y*height/8))
            hash=hash or (1L shl (y*8+x))
        var lapSum=0.0;var lapSq=0.0;var n=0;var clipped=0
        val color=DoubleArray(8)
        for(y in 1 until height-1) for(x in 1 until width-1) {
            val g=gray(x,y)
            val lap=gray(x-1,y)+gray(x+1,y)+gray(x,y-1)+gray(x,y+1)-4*g
            lapSum+=lap;lapSq+=lap*lap;n++
            if(g<5 || g>250) clipped++
            val p=pixels[y*width+x]
            val bucket=((p shr 23 and 1) shl 2) or ((p shr 15 and 1) shl 1) or (p shr 7 and 1)
            color[bucket]++
        }
        // Exposure is a small ranking signal; night/artistic photos are never discarded.
        return PhotoVisualFeatures(hash,max(0.0,lapSq/n-(lapSum/n).pow(2)).toFloat(),
            (1.0-clipped.toDouble()/n).toFloat(),color.map { (it/n).toFloat() })
    }
}
