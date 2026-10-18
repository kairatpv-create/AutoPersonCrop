package kz.autopersoncrop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.abs

/**
 * All 29 original/manual pairs in VideoFrames.zip (2026-10-18).
 * Registration uses image content, independently of the person detector and planner.
 * People envelopes were visually reviewed, including missed lying people and hands/feet.
 * Evidence is a CPU replay snapshot of the shipped YOLO asset + actual Kotlin multi-view
 * aggregation. It is a geometry regression fixture, not an Android/on-device inference test.
 * The older archive with spectators was superseded at the user's explicit request.
 */
@RunWith(Parameterized::class)
class ManualReferenceRegressionTest(private val reference: Reference) {
    data class Reference(
        val source: String,
        val image: ImageSize,
        val manual: PixelRect,
        val people: List<RectD>,
        val evidence: List<RectD>,
        val mcuWidth: Int,
        val mcuHeight: Int,
    ) {
        override fun toString() = source
    }

    private fun crop(): PixelRect = WrestlingCropPlanner.plan(
        reference.image,
        WrestlingSubjectSelector.select(reference.image, reference.evidence, controlledInput = true),
    )

    @Test
    fun allFourEdgesMatchTheRegisteredManualCrop() {
        val actual = crop()
        val expected = reference.manual
        close("left", actual.left, expected.left, reference.image.width * 0.03)
        close("top", actual.top, expected.top, reference.image.height * 0.03)
        close("right", actual.right, expected.right, reference.image.width * 0.03)
        close("bottom", actual.bottom, expected.bottom, reference.image.height * 0.03)
    }

    @Test
    fun areaAndAspectMatchTheRegisteredManualCrop() {
        val actual = crop()
        val expected = reference.manual
        val areaRatio = actual.width.toDouble() * actual.height / (expected.width.toDouble() * expected.height)
        val aspectRatio = (actual.width.toDouble() / actual.height) / (expected.width.toDouble() / expected.height)
        assertTrue("${reference.source}: area ratio $areaRatio", abs(areaRatio - 1.0) <= 0.16)
        assertTrue("${reference.source}: aspect ratio $aspectRatio", abs(aspectRatio - 1.0) <= 0.14)
    }

    @Test
    fun everyMainPersonRemainsInsideTheCrop() {
        val actual = crop()
        assertTrue(reference.people.size in 1..2)
        for (person in reference.people) {
            assertTrue("${reference.source}: a main person was clipped: $actual / $person",
                actual.left <= person.left + 2 && actual.top <= person.top + 2 &&
                    actual.right >= person.right - 2 && actual.bottom >= person.bottom - 2)
        }
    }

    @Test
    fun jpegOriginHasOnlyTheRequiredBlockAlignmentAllowance() {
        val actual = crop()
        // Native tjTransform requires an iMCU origin, not an iMCU far edge.
        // The extra allowance is a known encoding constraint on these two sides only.
        val left = actual.left / reference.mcuWidth * reference.mcuWidth
        val top = actual.top / reference.mcuHeight * reference.mcuHeight
        close("JPEG left", left, reference.manual.left, reference.image.width * 0.03 + reference.mcuWidth - 1)
        close("JPEG top", top, reference.manual.top, reference.image.height * 0.03 + reference.mcuHeight - 1)
        close("JPEG right", actual.right, reference.manual.right, reference.image.width * 0.03)
        close("JPEG bottom", actual.bottom, reference.manual.bottom, reference.image.height * 0.03)
    }

    @Test
    fun mirroredSceneHasMirroredFraming() {
        val image = reference.image
        val mirrored = reference.evidence.map { RectD(image.width - it.right, it.top, image.width - it.left, it.bottom) }
        val actual = WrestlingCropPlanner.plan(image, WrestlingSubjectSelector.select(image, mirrored, controlledInput = true))
        val original = crop()
        assertEquals(image.width - original.right, actual.left)
        assertEquals(image.width - original.left, actual.right)
        assertEquals(original.top, actual.top)
        assertEquals(original.bottom, actual.bottom)
    }

    @Test
    fun motionOnEitherPersonDoesNotChangeTheComposition() {
        for (focus in reference.people) {
            val actual = WrestlingCropPlanner.plan(reference.image,
                WrestlingSubjectSelector.select(reference.image, reference.evidence,
                    focus, controlledInput = true))
            close("motion left", actual.left, reference.manual.left, reference.image.width * 0.03)
            close("motion top", actual.top, reference.manual.top, reference.image.height * 0.03)
            close("motion right", actual.right, reference.manual.right, reference.image.width * 0.03)
            close("motion bottom", actual.bottom, reference.manual.bottom, reference.image.height * 0.03)
            for (person in reference.people) {
                assertTrue("${reference.source}: motion displaced the stationary partner",
                    actual.left <= person.left + 2 && actual.top <= person.top + 2 &&
                        actual.right >= person.right - 2 && actual.bottom >= person.bottom - 2)
            }
        }
    }

    private fun close(side: String, actual: Int, expected: Int, tolerance: Double) {
        assertTrue("${reference.source}: $side expected $expected, got $actual (tolerance $tolerance)",
            abs(actual - expected) <= tolerance)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun references(): Collection<Array<Any>> {
            val input = requireNotNull(ManualReferenceRegressionTest::class.java.getResourceAsStream("/manual/video_frames.tsv"))
            val rows = input.bufferedReader().use { it.readLines().drop(1).filter(String::isNotBlank) }
            require(rows.size == 29) { "Every VideoFrames reference must be present" }
            fun rect(text: String): RectD {
                val v = text.split(',').map(String::toDouble)
                return RectD(v[0], v[1], v[2], v[3])
            }
            return rows.map { line ->
                val c = line.split('\t')
                val m = c[5].split(',').map(String::toInt)
                arrayOf<Any>(Reference(c[1], ImageSize(c[3].toInt(), c[4].toInt()),
                    PixelRect(m[0], m[1], m[2], m[3]), c[6].split(';').map(::rect),
                    c[7].split(';').map(::rect), c[8].toInt(), c[9].toInt()))
            }
        }
    }
}
