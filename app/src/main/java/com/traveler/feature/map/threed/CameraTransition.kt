package com.traveler.feature.map.threed

import kotlin.math.*

/** Spherical interpolation stays finite at the date line and at antipodal endpoints. */
object CameraTransition {
    fun focus(a: Vec3, b: Vec3, fraction: Double): Vec3 {
        val t = fraction.coerceIn(0.0, 1.0)
        if (t == 0.0) return a
        if (t == 1.0) return b
        val u = a.unit(); val v = b.unit(); val dot = u.dot(v).coerceIn(-1.0, 1.0)
        val radius = a.length() * (1 - t) + b.length() * t
        val direction = when {
            dot > .9995 -> (u * (1 - t) + v * t).unit()
            dot < -.9995 -> {
                val seed = if (abs(u.y) < .9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
                val tangent = (seed - u * seed.dot(u)).unit()
                u * cos(PI * t) + tangent * sin(PI * t)
            }
            else -> { val angle = acos(dot); (u * sin((1 - t) * angle) + v * sin(t * angle)) * (1.0 / sin(angle)) }
        }
        return direction * radius
    }
}
