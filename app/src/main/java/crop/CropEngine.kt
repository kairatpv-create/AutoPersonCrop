package com.example.a01.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.a01.detector.Detector
import java.io.OutputStream

object CropEngine {

    fun processFolder(
        context: Context,
        folderUri: Uri
    ): Int {

        val detector = Detector(context)

        val folder =
            DocumentFile.fromTreeUri(
                context,
                folderUri
            ) ?: return 0

        var cropFolder: DocumentFile? = null

        folder.listFiles().forEach {

            if (
                it.isDirectory &&
                it.name == "CROP"
            ) {
                cropFolder = it
            }

        }

        if (cropFolder == null) {

            cropFolder =
                folder.createDirectory("CROP")

        }

        var saved = 0

        folder.listFiles().forEach { file ->

            val name =
                file.name?.lowercase() ?: ""

            if (
                !name.endsWith(".jpg") &&
                !name.endsWith(".jpeg") &&
                !name.endsWith(".png") &&
                !name.endsWith(".webp")
            ) {
                return@forEach
            }

            try {

                context.contentResolver
                    .openInputStream(file.uri)
                    ?.use { stream ->

                        val bitmap =
                            BitmapFactory
                                .decodeStream(stream)
                                ?: return@use

                        val detections =
                            detector.detect(bitmap)

                        Log.d(
                            "YOLO",
                            "Найдено ${detections.size}"
                        )

                        detections.forEachIndexed {

                                index,
                                detection ->

                            val crop =
                                CropUtils.cropPerson(

                                    bitmap,

                                    detection.box

                                )

                            val outFile =
                                cropFolder!!.createFile(

                                    "image/jpeg",

                                    file.nameWithoutExtension() +
                                            "_$index.jpg"

                                )

                            if (outFile != null) {

                                val output =
                                    context.contentResolver
                                        .openOutputStream(
                                            outFile.uri
                                        )

                                saveBitmap(
                                    crop,
                                    output
                                )

                                saved++

                            }

                        }

                    }

            } catch (e: Exception) {

                Log.e(
                    "YOLO",
                    "Ошибка",
                    e
                )

            }

        }

        return saved

    }

    private fun saveBitmap(

        bitmap: Bitmap,

        output: OutputStream?

    ) {

        output ?: return

        bitmap.compress(

            Bitmap.CompressFormat.JPEG,

            95,

            output

        )

        output.flush()

        output.close()

    }

    private fun DocumentFile.nameWithoutExtension(): String {

        val n =
            name ?: "image"

        val p =
            n.lastIndexOf('.')

        if (p == -1)
            return n

        return n.substring(0, p)

    }

}