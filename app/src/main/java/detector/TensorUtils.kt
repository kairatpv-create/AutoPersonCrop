package com.example.a01.detector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.graphics.Bitmap
import java.nio.FloatBuffer

object TensorUtils {

    private const val INPUT_SIZE = 640

    fun bitmapToTensor(
        env: OrtEnvironment,
        bitmap: Bitmap
    ): OnnxTensor {

        val resized = Bitmap.createScaledBitmap(
            bitmap,
            INPUT_SIZE,
            INPUT_SIZE,
            true
        )

        val pixels =
            IntArray(INPUT_SIZE * INPUT_SIZE)

        resized.getPixels(
            pixels,
            0,
            INPUT_SIZE,
            0,
            0,
            INPUT_SIZE,
            INPUT_SIZE
        )

        val input =
            FloatArray(
                3 * INPUT_SIZE * INPUT_SIZE
            )

        var rIndex = 0
        var gIndex = INPUT_SIZE * INPUT_SIZE
        var bIndex = INPUT_SIZE * INPUT_SIZE * 2

        for (pixel in pixels) {

            input[rIndex++] =
                ((pixel shr 16) and 0xFF) / 255f

            input[gIndex++] =
                ((pixel shr 8) and 0xFF) / 255f

            input[bIndex++] =
                (pixel and 0xFF) / 255f

        }

        val shape =
            longArrayOf(
                1,
                3,
                INPUT_SIZE.toLong(),
                INPUT_SIZE.toLong()
            )

        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            shape
        )

    }

}