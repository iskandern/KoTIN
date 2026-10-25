package dev.iskandern.kotin.model

import kotlin.math.PI

fun toRadians(degrees: Double): Double = degrees * PI / 180

fun toDegrees(radians: Double): Double = radians * 180 / PI
