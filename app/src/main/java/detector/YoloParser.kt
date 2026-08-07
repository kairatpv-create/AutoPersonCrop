package com.example.a01.detector

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

object YoloParser {

    private const val CONFIDENCE_THRESHOLD = 0.35f
    private const val PERSON_CLASS = 0

    fun parse(
        output: Array<FloatArray>,
        imageWidth: Int,
        imageHeight: Int
    ): List<Detection> {

        val detections = mutableListOf<Detection>()

        if (output.size != 84)
            return detections

        val count = output[0].size

        for (i in 0 until count) {

            val cx = output[0][i]
            val cy = output[1][i]
            val w = output[2][i]
            val h = output[3][i]

            var bestClass = -1
            var bestScore = 0f

            for (c in 4 until 84) {

                val score = output[c][i]

                if (score > bestScore) {
                    bestScore = score
                    bestClass = c - 4
                }

            }

            if (bestClass != PERSON_CLASS)
                continue

            if (bestScore < CONFIDENCE_THRESHOLD)
                continue

            val left =
                (cx - w / 2f) * imageWidth

            val top =
                (cy - h / 2f) * imageHeight

            val right =
                (cx + w / 2f) * imageWidth

            val bottom =
                (cy + h / 2f) * imageHeight

            detections.add(

                Detection(

                    box = RectF(

                        max(0f, left),

                        max(0f, top),

                        min(imageWidth.toFloat(), right),

                        min(imageHeight.toFloat(), bottom)

                    ),

                    confidence = bestScore,

                    classId = PERSON_CLASS,

                    label = "person"

                )

            )

        }

        return nonMaximumSuppression(detections)

    }

    private fun nonMaximumSuppression(

        detections: List<Detection>

    ): List<Detection> {

        val result = mutableListOf<Detection>()

        val sorted =
            detections.sortedByDescending {

                it.confidence

            }.toMutableList()

        while (sorted.isNotEmpty()) {

            val first =
                sorted.removeAt(0)

            result.add(first)

            val iterator =
                sorted.iterator()

            while (iterator.hasNext()) {

                val other =
                    iterator.next()

                if (
                    iou(
                        first.box,
                        other.box
                    ) > 0.45f
                ) {
                    iterator.remove()
                }

            }

        }

        return result

    }

    private fun iou(
        a: RectF,
        b: RectF
    ): Float {

        val left =
            max(a.left, b.left)

        val top =
            max(a.top, b.top)

        val right =
            min(a.right, b.right)

        val bottom =
            min(a.bottom, b.bottom)

        val width =
            max(0f, right - left)

        val height =
            max(0f, bottom - top)

        val intersection =
            width * height

        val union =
            a.width() * a.height() +
                    b.width() * b.height() -
                    intersection

        if (union <= 0f)
            return 0f

        return intersection / union

    }

}