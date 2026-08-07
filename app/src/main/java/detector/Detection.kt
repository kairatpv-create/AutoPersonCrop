package com.example.a01.detector

import android.graphics.RectF

data class Detection(

    val box: RectF,

    val confidence: Float,

    val classId: Int,

    val label: String = "person"

)