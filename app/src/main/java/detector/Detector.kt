package com.example.a01.detector

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

class Detector(
    context: Context
) {

    private val env =
        OrtEnvironment.getEnvironment()

    private val session: OrtSession

    init {

        val model =
            context.assets
                .open("yolo11n.onnx")
                .readBytes()

        session =
            env.createSession(
                model,
                OrtSession.SessionOptions()
            )

    }

    fun isLoaded(): Boolean {

        return true

    }

    fun detect(
        bitmap: Bitmap
    ): List<Detection> {

        val inputTensor =
            TensorUtils.bitmapToTensor(
                env,
                bitmap
            )

        val results =
            session.run(

                mapOf(

                    session.inputNames.first()
                            to inputTensor

                )

            )

        val outputTensor =
            results[0] as OnnxTensor

        val value =
            outputTensor.value

        val detections =
            when (value) {

                is Array<*> -> {

                    val first =
                        value[0]

                    when (first) {

                        is Array<*> -> {

                            @Suppress("UNCHECKED_CAST")

                            val output =
                                first as Array<FloatArray>

                            YoloParser.parse(

                                output,

                                bitmap.width,

                                bitmap.height

                            )

                        }

                        else -> {

                            emptyList()

                        }

                    }

                }

                else -> {

                    emptyList()

                }

            }

        inputTensor.close()

        outputTensor.close()

        results.close()

        return detections

    }

}