package nl.plantscan.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Half
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Iris 10, by brunopaiva15 (CC BY 4.0).
 * Reimplements the documented two-network inference, reference lookup and fusion.
 * The Android bitmap resizer approximates the source's Lanczos/bicubic recipe.
 * Report scores are model scores, not calibrated field certainty.
 */
class Iris10Classifier(context: Context) : Closeable {
    private val meta9 = JSONObject(context.assets.open("model.json").bufferedReader().use { it.readText() })
    private val meta10 = JSONObject(context.assets.open("iris10.json").bufferedReader().use { it.readText() })
    private val labels = context.assets.open("labels.txt").bufferedReader().use { it.readLines().filter(String::isNotBlank) }
    private val speciesIds = meta10.getJSONArray("especes")
    private val referenceRows = meta10.getJSONArray("lignes")
    private val map9 = meta10.getJSONArray("iris9")
    private val references: FloatArray
    private val model9: Interpreter
    private val model10: Interpreter
    private val dim = meta10.optInt("dim", 1024)
    private val temperature = meta10.optDouble("temperature", 100.0)
    private val blend = meta10.getJSONObject("fusion").optDouble("poids_iris9", 0.5)
    private val floor = meta10.getJSONObject("fusion").optDouble("plancher", 1e-10)
    private val outdoor: Set<String> = meta9.optJSONObject("masks")?.optJSONArray("outdoor")?.let { a ->
        (0 until a.length()).map { a.optString(it) }.toSet()
    } ?: emptySet()

    init {
        require(labels.size == map9.length()) { "Iris 9 metadata komen niet overeen" }
        val raw = context.assets.open("iris10-references.bin").use { it.readBytes() }
        require(raw.size == referenceRows.length() * dim * 2) { "Iris 10 referenties zijn onvolledig" }
        val shorts = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        references = FloatArray(referenceRows.length() * dim) { Half.toFloat(shorts.get()) }
        model9 = openModel(context, "plants.tflite")
        model10 = openModel(context, "iris10.tflite")
    }

    private fun openModel(context: Context, name: String): Interpreter {
        val bytes = context.assets.open(name).use { it.readBytes() }
        val data = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        data.put(bytes); data.rewind()
        return Interpreter(data, Interpreter.Options().setNumThreads(2))
    }

    @Synchronized
    fun identifyMany(images: List<Bitmap>): List<SpeciesGuess> {
        require(images.isNotEmpty()) { "Maak eerst een duidelijke plantenfoto" }
        val merged = FloatArray(speciesIds.length())
        val input = images.take(3)
        for (image in input) {
            val one = identifyDistribution(image)
            for (i in merged.indices) merged[i] += one[i] / input.size
        }
        val species = meta9.getJSONObject("species")
        return merged.indices.sortedByDescending { merged[it] }.take(5).map { i ->
            val code = speciesIds.getString(i)
            SpeciesGuess(species.optString(code, code), merged[i])
        }
    }

    private fun identifyDistribution(bitmap: Bitmap): FloatArray {
        val p9 = FloatArray(labels.size)
        val nine = Array(1) { p9 }
        model9.run(toInput(preprocess9(bitmap), false), nine)
        val vector = FloatArray(dim)
        model10.run(toInput(preprocess10(bitmap), true), Array(1) { vector })
        var norm = 0.0
        for (v in vector) norm += v * v
        val inverseNorm = 1.0 / sqrt(max(norm, 1e-14))

        val best = FloatArray(speciesIds.length()) { -Float.MAX_VALUE }
        for (row in 0 until referenceRows.length()) {
            var dot = 0.0
            val offset = row * dim
            for (i in 0 until dim) dot += references[offset + i] * vector[i]
            val idx = referenceRows.getInt(row)
            best[idx] = max(best[idx], (dot * inverseNorm).toFloat())
        }

        val peak = best.maxOrNull() ?: 0f
        val p10 = FloatArray(best.size) { i -> exp(temperature * (best[i] - peak)).toFloat() }
        normalize(p10)

        val p9s = FloatArray(best.size)
        for (i in p9.indices) {
            val weight = if (outdoor.isEmpty() || labels[i] in outdoor) 1.0f else 0.25f
            p9s[map9.getInt(i)] += p9[i] * weight
        }
        normalize(p9s)

        val fused = FloatArray(best.size) { i ->
            (blend * ln(max(p9s[i].toDouble(), floor)) +
                (1.0 - blend) * ln(max(p10[i].toDouble(), floor))).toFloat()
        }
        val highest = fused.maxOrNull() ?: 0f
        for (i in fused.indices) fused[i] = exp((fused[i] - highest).toDouble()).toFloat()
        normalize(fused)
        return fused
    }

    private fun normalize(values: FloatArray) {
        val sum = values.sum().coerceAtLeast(1e-12f)
        for (i in values.indices) values[i] /= sum
    }

    private fun preprocess9(image: Bitmap): Bitmap {
        val side = min(image.width, image.height)
        val square = Bitmap.createBitmap(image, (image.width-side)/2, (image.height-side)/2, side, side)
        val first = if (side > 448) Bitmap.createScaledBitmap(square, 448, 448, true) else square
        val next = Bitmap.createScaledBitmap(first, 366, 366, true)
        val crop = Bitmap.createBitmap(next, 23, 23, 320, 320)
        if (first !== square) first.recycle()
        if (square !== image && square !== first) square.recycle()
        next.recycle()
        return crop
    }

    private fun preprocess10(image: Bitmap): Bitmap {
        val side = max(image.width, image.height)
        val ratio = min(1.0f, 384f / side)
        val shrunk = if (ratio < 1) Bitmap.createScaledBitmap(
            image, max(1, (image.width * ratio).toInt()),
            max(1, (image.height * ratio).toInt()), true
        ) else image
        val shortSide = min(shrunk.width, shrunk.height)
        val square = Bitmap.createBitmap(shrunk,
            (shrunk.width - shortSide)/2, (shrunk.height - shortSide)/2, shortSide, shortSide)
        val crop = Bitmap.createScaledBitmap(square, 320, 320, true)
        if (square !== image && square !== crop) square.recycle()
        if (shrunk !== image && shrunk !== square) shrunk.recycle()
        return crop
    }

    private fun toInput(patch: Bitmap, unitScale: Boolean): ByteBuffer {
        val input = ByteBuffer.allocateDirect(320*320*3*4).order(ByteOrder.nativeOrder())
        val px = IntArray(320*320)
        patch.getPixels(px, 0, 320, 0, 0, 320, 320)
        val scale = if (unitScale) 1f / 255f else 1f
        for (color in px) {
            input.putFloat(Color.red(color) * scale)
            input.putFloat(Color.green(color) * scale)
            input.putFloat(Color.blue(color) * scale)
        }
        patch.recycle()
        input.rewind()
        return input
    }

    override fun close() {
        model10.close()
        model9.close()
    }
}
