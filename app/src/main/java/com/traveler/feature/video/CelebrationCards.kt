package com.traveler.feature.video

import android.graphics.*
import kotlin.math.*

/** Resolution-independent, deterministic motion graphics, shared by all export aspect ratios. */
internal object CelebrationCards {
    fun draw(canvas: Canvas, width: Int, height: Int, title: String, subtitle: String,
             badges: List<String>, ending: Boolean, seconds: Float, alpha: Float) {
        if (alpha <= 0f) return
        val w = width.toFloat(); val h = height.toFloat(); val u = min(w, h) / 360f
        val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (alpha * 255).toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(0xEC111A47.toInt(), 0xDD48207E.toInt(), 0xE20C5063.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p); p.shader = null
        val palette = intArrayOf(0xFFFFD166.toInt(), 0xFF53E4CF.toInt(), 0xFFF778D3.toInt(), 0xFF9CA9FF.toInt())
        // Radiant beams and orbit rings sit behind the typography.
        for (i in 0 until 18) {
            val angle = i * PI / 9 + seconds * .045
            val radius = max(w, h)
            val path = Path().apply {
                moveTo(w * .5f, h * .5f)
                lineTo((w*.5 + cos(angle)*radius).toFloat(), (h*.5 + sin(angle)*radius).toFloat())
                lineTo((w*.5 + cos(angle+.06)*radius).toFloat(), (h*.5 + sin(angle+.06)*radius).toFloat()); close()
            }
            p.color = 0x0FFFFFFF; canvas.drawPath(path, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = u
        for (i in 0..2) {
            p.color = 0x4264E9DF
            canvas.drawCircle(w*.5f, h*.5f, min(w,h)*(.37f+i*.16f), p)
        }
        p.style = Paint.Style.FILL
        for (i in 0 until 64) {
            val x = ((i * 137.508 + sin(seconds*.7+i)*8) % 360 / 360 * w).toFloat()
            val y = (((i * 83.7 + seconds*13) % 500) / 500 * h).toFloat()
            p.color = palette[i % palette.size]
            canvas.save(); canvas.rotate(i*31f+seconds*17, x, y)
            if (i % 3 == 0) {
                canvas.drawRect(x-4*u,y-u,x+4*u,y+u,p); canvas.drawRect(x-u,y-4*u,x+u,y+4*u,p)
            } else canvas.drawRoundRect(x-1.5f*u,y-3*u,x+1.5f*u,y+3*u,u,u,p)
            canvas.restore()
        }
        val landscape = w > h
        val rect = if (landscape) RectF(w*.16f,h*.13f,w*.84f,h*.87f)
            else RectF(w*.065f,h*.22f,w*.935f,h*.78f)
        val rh = rect.height(); val rw = rect.width()
        p.color = 0x70000000; canvas.drawRoundRect(RectF(rect).apply { offset(0f,8*u) },22*u,22*u,p)
        p.shader = LinearGradient(rect.left,rect.top,rect.right,rect.bottom,
            intArrayOf(0xFF4930A2.toInt(),0xFF202251.toInt(),0xFF15516A.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect,22*u,22*u,p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 2*u; p.color = 0xFFFFD166.toInt()
        canvas.drawRoundRect(rect,22*u,22*u,p)
        p.strokeWidth = .6f*u; p.color = 0x7770E9DF
        canvas.drawRoundRect(RectF(rect).apply { inset(6*u,6*u) },17*u,17*u,p)
        p.style = Paint.Style.FILL
        fun text(value:String,y:Float,size:Float,color:Int=Color.WHITE,bold:Boolean=true,available:Float=rw*.86f) {
            p.color=color; p.typeface=if(bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            p.textAlign=Paint.Align.CENTER; p.textSize=size
            val measured=p.measureText(value)
            if(measured>available) p.textSize*=available/measured
            canvas.drawText(value,rect.centerX(),y,p)
        }
        val ribbon = RectF(rect.centerX()-rw*.29f,rect.top-10*u,rect.centerX()+rw*.29f,rect.top+16*u)
        p.color=0xFFFFD166.toInt(); canvas.drawRoundRect(ribbon,8*u,8*u,p)
        text(if(ending) "★  MEMORY COLLECTION  ★" else "★  THE ADVENTURE BEGINS  ★",rect.top+7*u,10*u,0xFF252450.toInt(),available=ribbon.width()*.91f)
        text(if(ending) "WHAT A" else "TRAVEL",rect.top+rh*.20f, min(39*u,rh*.15f),0xFF64E9DF.toInt())
        text(if(ending) "JOURNEY!" else "DIARY",rect.top+rh*.39f,min(62*u,rh*.19f))
        // A separate title band gives custom names clear hierarchy over decorative headings.
        p.color=0x4D060C2E; canvas.drawRoundRect(rect.left+12*u,rect.top+rh*.45f,rect.right-12*u,rect.top+rh*.64f,10*u,10*u,p)
        text(title,rect.top+rh*.575f,26*u,0xFFFFD166.toInt())
        text(subtitle,rect.top+rh*.71f,12*u,bold=false)
        val gap=6*u; val bw=(rw*.88f-gap*(badges.size-1))/badges.size
        for((i,badge) in badges.withIndex()) {
            val left=rect.left+rw*.06f+i*(bw+gap)
            val badgeRect=RectF(left,rect.top+rh*.78f,left+bw,rect.top+rh*.88f)
            p.color=palette[(i+1)%palette.size]; canvas.drawRoundRect(badgeRect,7*u,7*u,p)
            p.typeface=Typeface.DEFAULT_BOLD; p.textSize=11*u; p.textAlign=Paint.Align.CENTER; p.color=0xFF142846.toInt()
            val measured=p.measureText(badge); if(measured>bw-8*u) p.textSize*=(bw-8*u)/measured
            canvas.drawText(badge,badgeRect.centerX(),badgeRect.centerY()-(p.ascent()+p.descent())/2,p)
        }
        text(if(ending) "MY TRAVEL DIARY 3D  ·  UNTIL NEXT TIME" else "YOUR ROUTE. YOUR MOMENTS. YOUR STORY.",rect.top+rh*.95f,8*u,0xFFBCECE8.toInt())
        canvas.restoreToCount(layer)
    }
}
