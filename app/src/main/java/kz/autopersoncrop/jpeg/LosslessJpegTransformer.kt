package kz.autopersoncrop.jpeg

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kz.autopersoncrop.core.PixelRect

/**
 * Strict JPEG transform boundary. The JNI module uses libjpeg-turbo coefficient transforms.
 * It must never decode + re-encode JPEG pixels.
 */
class LosslessJpegTransformer(private val context: Context) {
    init {
        check(NativeBridge.available) {
            "Модуль lossless JPEG не загружен. Пересжатие запрещено, поэтому обработка остановлена."
        }
    }

    fun crop(source: Uri, destinationDir: DocumentFile, outputName: String, rawRect: PixelRect): Uri {
        val bytes = context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Не удалось открыть исходный JPEG" }
            input.readBytes()
        }
        val transformed = NativeBridge.losslessCrop(
            bytes,
            rawRect.left, rawRect.top, rawRect.width, rawRect.height
        )
        val existing = destinationDir.findFile(outputName)
        existing?.let { check(it.delete()) { "Не удалось заменить старый результат $outputName" } }
        val out = destinationDir.createFile("image/jpeg", outputName)
            ?: error("Не удалось создать $outputName")
        try {
            context.contentResolver.openOutputStream(out.uri, "w").use { stream ->
                requireNotNull(stream)
                stream.write(transformed)
                stream.flush()
            }
            return out.uri
        } catch (t: Throwable) {
            runCatching { out.delete() }
            throw t
        }
    }

    private object NativeBridge {
        val available: Boolean
        init {
            available = try {
                System.loadLibrary("autocropjpeg")
                true
            } catch (_: Throwable) { false }
        }
        external fun losslessCrop(jpeg: ByteArray, x: Int, y: Int, width: Int, height: Int): ByteArray
    }
}
