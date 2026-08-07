package com.example.a01.model

import android.graphics.RectF

data class DetectionResult(
    val rect: RectF,
    val confidence: Float,
    val classId: Int
)