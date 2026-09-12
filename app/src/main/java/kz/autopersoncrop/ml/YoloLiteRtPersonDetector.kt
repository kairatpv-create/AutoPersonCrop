package kz.autopersoncrop.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kz.autopersoncrop.core.RectD
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Offline YOLO detector using Google LiteRT 2.x CompiledModel API. */
class YoloLiteRtPersonDetector(
    private val context: Context,
    assetName: String = "person_detector.tflite",
    private val confidence: Float = 0.30f,
    private val iouThreshold: Float = 0.70f,
    useGpu: Boolean = true,
) : PersonDetector {
    private val modelFile: File = copyAssetIfNeeded(assetName)
    private val compiled: CompiledModel
    private val inputBuffers: List<TensorBuffer>
    private val outputBuffers: List<TensorBuffer>
    private val inputW: Int
    private val inputH: Int
    private val inputUsesNchw: Boolean
    private val outputShape: IntArray

    private val modelBitmap: Bitmap
    private val modelCanvas: Canvas
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixelBuffer: IntArray
    private val inputFloatBuffer: FloatArray

    val accelerator: String

    init {
        val prepared = prepareModel(useGpu)
        compiled = prepared.model
        inputBuffers = prepared.inputs
        outputBuffers = prepared.outputs
        inputW = prepared.inputW
        inputH = prepared.inputH
        inputUsesNchw = prepared.nchw
        outputShape = prepared.outputShape
        accelerator = prepared.accelerator

        modelBitmap = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
        modelCanvas = Canvas(modelBitmap)
        pixelBuffer = IntArray(inputW * inputH)
        inputFloatBuffer = FloatArray(inputW * inputH * 3)

        Log.i(TAG, "LiteRT $accelerator input=${if (inputUsesNchw) "NCHW" else "NHWC"} ${inputW}x$inputH output=${outputShape.contentToString()}")
    }

    private data class Prepared(
        val model: CompiledModel,
        val inputs: List<TensorBuffer>,
        val outputs: List<TensorBuffer>,
        val inputW: Int,
        val inputH: Int,
        val nchw: Boolean,
        val outputShape: IntArray,
        val accelerator: String,
    )

    private fun prepareModel(wantGpu: Boolean): Prepared {
        var last: Throwable? = null
        val order = if (wantGpu) listOf(Accelerator.GPU to "GPU", Accelerator.CPU to "CPU")
        else listOf(Accelerator.CPU to "CPU")

        for ((acc, accName) in order) {
            var model: CompiledModel? = null
            var inputs: List<TensorBuffer>? = null
            var outputs: List<TensorBuffer>? = null
            try {
                val options = CompiledModel.Options(acc)
                if (acc == Accelerator.GPU) {
                    options.gpuOptions = CompiledModel.GpuOptions(
                        serializationDir = context.codeCacheDir.absolutePath,
                        modelCacheKey = "${modelFile.name}_${modelFile.length()}",
                        serializeProgramCache = true,
                    )
                } else {
                    options.cpuOptions = CompiledModel.CpuOptions(
                        numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
                    )
                }
                model = CompiledModel.create(modelFile.absolutePath, options)
                inputs = model.createInputBuffers()
                outputs = model.createOutputBuffers()

                val nativeDims = findInputDims(model, inputs[0])
                val nchw = nativeDims.size >= 4 && nativeDims[1] == 3 && nativeDims.last() != 3
                val h = if (nchw) nativeDims[2] else nativeDims[1]
                val w = if (nchw) nativeDims[3] else nativeDims[2]
                require(w > 0 && h > 0) { "Некорректный размер входа модели: ${nativeDims.contentToString()}" }

                inputs[0].writeFloat(FloatArray(w * h * 3))
                model.run(inputs, outputs)
                val firstOut = outputs[0].readFloat()
                val shape = findOutputDims(model, firstOut.size)
                return Prepared(model, inputs, outputs, w, h, nchw, shape, accName)
            } catch (t: Throwable) {
                last = t
                Log.w(TAG, "$accName не запустил модель: ${t.message}")
                inputs?.forEach { runCatching { it.close() } }
                outputs?.forEach { runCatching { it.close() } }
                runCatching { model?.close() }
            }
        }
        throw IllegalStateException("Не удалось запустить офлайн-модель на GPU или CPU", last)
    }

    private fun findInputDims(model: CompiledModel, input: TensorBuffer): IntArray {
        val names = listOf("images", "args_0", "input", "input_1", "serving_default_input")
        for (name in names) {
            val dims = runCatching {
                model.getInputTensorType(inputName = name).layout?.dimensions?.toIntArray()
            }.getOrNull()
            if (dims != null && dims.size >= 4) return dims
        }
        val count = input.readFloat().size
        val side = if (count > 0 && count % 3 == 0) sqrt((count / 3.0)).roundToInt() else 0
        require(side > 0 && side * side * 3 == count) {
            "Не удалось определить форму входа LiteRT ($count элементов)"
        }
        return intArrayOf(1, side, side, 3)
    }

    private fun findOutputDims(model: CompiledModel, count: Int): IntArray {
        val names = listOf("output_0", "output0", "Identity")
        for (name in names) {
            val dims = runCatching {
                model.getOutputTensorType(outputName = name).layout?.dimensions?.toIntArray()
            }.getOrNull()
            if (dims != null && dims.isNotEmpty()) return dims
        }
        val features = 84
        if (count % features == 0) return intArrayOf(1, features, count / features)
        if (count % 6 == 0) return intArrayOf(1, count / 6, 6)
        error("Неизвестный выход YOLO: $count float")
    }

    override fun detect(bitmap: Bitmap): List<RectD> {
        val prep = letterboxIntoReusableBitmap(bitmap)
        fillInputFloatBuffer()
        inputBuffers[0].writeFloat(inputFloatBuffer)
        compiled.run(inputBuffers, outputBuffers)
        val out = outputBuffers[0].readFloat()
        return nms(decode(out, prep)).map { it.rect }
    }

    private data class Candidate(val rect: RectD, val score: Float)
    private data class Prep(
        val scale: Double,
        val padX: Double,
        val padY: Double,
        val srcW: Int,
        val srcH: Int,
    )

    private fun letterboxIntoReusableBitmap(src: Bitmap): Prep {
        val scale = min(inputW.toDouble() / src.width, inputH.toDouble() / src.height)
        val nw = (src.width * scale).roundToInt().coerceAtLeast(1)
        val nh = (src.height * scale).roundToInt().coerceAtLeast(1)
        val dx = (inputW - nw) / 2
        val dy = (inputH - nh) / 2

        modelCanvas.drawColor(Color.rgb(114, 114, 114))
        modelCanvas.drawBitmap(
            src,
            null,
            RectF(dx.toFloat(), dy.toFloat(), (dx + nw).toFloat(), (dy + nh).toFloat()),
            drawPaint,
        )
        return Prep(scale, dx.toDouble(), dy.toDouble(), src.width, src.height)
    }

    private fun fillInputFloatBuffer() {
        modelBitmap.getPixels(pixelBuffer, 0, inputW, 0, 0, inputW, inputH)
        if (inputUsesNchw) {
            val plane = inputW * inputH
            for (i in pixelBuffer.indices) {
                val p = pixelBuffer[i]
                inputFloatBuffer[i] = ((p shr 16) and 0xFF) / 255f
                inputFloatBuffer[plane + i] = ((p shr 8) and 0xFF) / 255f
                inputFloatBuffer[2 * plane + i] = (p and 0xFF) / 255f
            }
        } else {
            var j = 0
            for (p in pixelBuffer) {
                inputFloatBuffer[j++] = ((p shr 16) and 0xFF) / 255f
                inputFloatBuffer[j++] = ((p shr 8) and 0xFF) / 255f
                inputFloatBuffer[j++] = (p and 0xFF) / 255f
            }
        }
    }

    private fun decode(out: FloatArray, p: Prep): List<Candidate> {
        if (outputShape.size == 3 && outputShape[2] == 6) {
            val rows = outputShape[1]
            return buildList {
                for (i in 0 until rows) {
                    val o = i * 6
                    val conf = out[o + 4]
                    val cls = out[o + 5].toInt()
                    if (cls != 0 || conf < confidence) continue
                    mapXyxy(out[o], out[o + 1], out[o + 2], out[o + 3], p)?.let {
                        add(Candidate(it, conf))
                    }
                }
            }
        }

        require(outputShape.size == 3) { "Неподдерживаемый output YOLO: ${outputShape.contentToString()}" }
        val a = outputShape[1]
        val b = outputShape[2]
        val featureMajor = a < b
        val features = if (featureMajor) a else b
        val anchors = if (featureMajor) b else a
        require(features >= 5) { "Неподдерживаемый YOLO output: ${outputShape.contentToString()}" }

        fun v(feature: Int, anchor: Int): Float =
            if (featureMajor) out[feature * anchors + anchor] else out[anchor * features + feature]

        val list = ArrayList<Candidate>()
        for (i in 0 until anchors) {
            val conf = v(4, i)
            if (conf < confidence) continue
            val cx = v(0, i)
            val cy = v(1, i)
            val w = v(2, i)
            val h = v(3, i)
            mapXyxy(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, p)?.let {
                list += Candidate(it, conf)
            }
        }
        return list
    }

    private fun mapXyxy(x1: Float, y1: Float, x2: Float, y2: Float, p: Prep): RectD? {
        val normalized = max(max(abs(x1), abs(x2)), max(abs(y1), abs(y2))) <= 2.5f
        val xx1 = if (normalized) x1 * inputW else x1
        val xx2 = if (normalized) x2 * inputW else x2
        val yy1 = if (normalized) y1 * inputH else y1
        val yy2 = if (normalized) y2 * inputH else y2
        val l = ((xx1 - p.padX) / p.scale).coerceIn(0.0, p.srcW.toDouble())
        val t = ((yy1 - p.padY) / p.scale).coerceIn(0.0, p.srcH.toDouble())
        val r = ((xx2 - p.padX) / p.scale).coerceIn(0.0, p.srcW.toDouble())
        val b = ((yy2 - p.padY) / p.scale).coerceIn(0.0, p.srcH.toDouble())
        return if (r > l && b > t) RectD(l, t, r, b) else null
    }

    private fun nms(input: List<Candidate>): List<Candidate> {
        val sorted = input.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Candidate>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep += best
            val it = sorted.iterator()
            while (it.hasNext()) {
                if (iou(best.rect, it.next().rect) > iouThreshold) it.remove()
            }
        }
        return keep
    }

    private fun iou(a: RectD, b: RectD): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bot = min(a.bottom, b.bottom)
        val inter = max(0.0, r - l) * max(0.0, bot - t)
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0) 0f else (inter / union).toFloat()
    }

    private fun copyAssetIfNeeded(assetName: String): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(dir, assetName)
        val assetLength = runCatching { context.assets.openFd(assetName).declaredLength }.getOrNull()
        if (!target.exists() || (assetLength != null && target.length() != assetLength)) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(target, false).use { output -> input.copyTo(output) }
            }
        }
        return target
    }

    override fun close() {
        inputBuffers.forEach { runCatching { it.close() } }
        outputBuffers.forEach { runCatching { it.close() } }
        runCatching { compiled.close() }
        runCatching { modelBitmap.recycle() }
    }

    companion object { private const val TAG = "AutoPersonCropML" }
}
