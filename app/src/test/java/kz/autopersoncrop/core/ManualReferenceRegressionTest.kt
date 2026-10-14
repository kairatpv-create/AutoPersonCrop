package kz.autopersoncrop.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Geometry fixtures measured from the user's JPEG-original/manual-JPG pairs (October 14, 2026).
 * Subject bounds are conservatively annotated human envelopes in ORIGINAL image coordinates.
 * The file identifiers are ONLY fixture documentation, not crop-production conditionals.
 *
 * A separate image-registration report covers all 302 matched image pairs. Those photos are not
 * packaged into the APK or its CI tests; this JVM suite tests the actual composition planner.
 */
class ManualReferenceRegressionTest {
    private data class Reference(
        val scene: String,
        val source: ImageSize,
        val people: List<RectD>,
        val manual: PixelRect,
    )

    private val references = listOf(
        Reference(
            "single standing dancer / IMG_20261014_093039vf132000.jpeg",
            ImageSize(1280, 720),
            listOf(RectD(705.0, 27.0, 883.0, 705.0)),
            PixelRect(554, 0, 1043, 720),
        ),
        Reference(
            "horizontal single person / IMG_20261014_092321vf149000.jpeg",
            ImageSize(1280, 720),
            listOf(RectD(200.0, 82.0, 1035.0, 577.0)),
            PixelRect(157, 44, 1088, 629),
        ),
        Reference(
            "two standing-and-seated people / IMG_20261014_085843vf130000.jpeg",
            ImageSize(1920, 1080),
            listOf(
                RectD(840.0, 159.0, 1092.0, 750.0),
                RectD(950.0, 490.0, 1242.0, 905.0),
            ),
            PixelRect(777, 123, 1285, 927),
        ),
        Reference(
            "overlapping wrestlers / IMG_20261014_085129vf278000.jpeg",
            ImageSize(1920, 1080),
            listOf(
                RectD(655.0, 105.0, 1240.0, 925.0),
                RectD(1000.0, 175.0, 1548.0, 974.0),
            ),
            PixelRect(574, 61, 1614, 1019),
        ),
        Reference(
            "one horizontal dancer / IMG_20261014_092949vf14000.jpeg",
            ImageSize(1280, 720),
            listOf(RectD(195.0, 348.0, 690.0, 622.0)),
            PixelRect(165, 295, 712, 648),
        ),
        Reference(
            "edge-constrained dynamic pose / IMG_20261014_093009vf61000.jpeg",
            ImageSize(1280, 720),
            listOf(RectD(355.0, 150.0, 1031.0, 695.0)),
            PixelRect(328, 112, 1071, 720),
        ),
    )

    @Test
    fun framingMatchesMeasuredManualCropEdgesAndArea() {
        for (ref in references) {
            val actual = WrestlingCropPlanner.plan(ref.source, ref.people)
            val expected = ref.manual
            val toleranceX = ref.source.width * 0.03
            val toleranceY = ref.source.height * 0.03

            fun close(axis: String, a: Int, b: Int, tolerance: Double) {
                assertTrue(ref.scene + ": " + axis + " expected " + b + ", got " + a,
                    abs(a - b) <= tolerance)
            }

            close("left", actual.left, expected.left, toleranceX)
            close("top", actual.top, expected.top, toleranceY)
            close("right", actual.right, expected.right, toleranceX)
            close("bottom", actual.bottom, expected.bottom, toleranceY)

            val expectedArea = expected.width.toDouble() * expected.height
            val actualArea = actual.width.toDouble() * actual.height
            assertTrue(ref.scene + ": crop area exceeds reference tolerance",
                abs(actualArea - expectedArea) / expectedArea <= 0.16)
            val expectedAspect = expected.width.toDouble() / expected.height
            val actualAspect = actual.width.toDouble() / actual.height
            assertTrue(ref.scene + ": aspect differs excessively",
                abs(actualAspect - expectedAspect) / expectedAspect <= 0.14)

            for (person in ref.people) {
                assertTrue(ref.scene + ": a main person is clipped",
                    actual.left <= person.left + 1 &&
                        actual.top <= person.top + 1 &&
                        actual.right >= person.right - 1 &&
                        actual.bottom >= person.bottom - 1)
            }
        }
    }

    @Test
    fun falseExtraEvidenceDoesNotExpandMainPairFraming() {
        val image = ImageSize(1280, 720)
        val primary = RectD(290.0, 80.0, 545.0, 655.0)
        val other = RectD(530.0, 100.0, 790.0, 640.0)
        val remoteJudge = RectD(1040.0, 70.0, 1215.0, 650.0)
        val selected = WrestlingSubjectSelector.select(image, listOf(primary, remoteJudge, other))
        assertTrue(selected.contains(primary))
        assertTrue(selected.contains(other))
        assertTrue(!selected.contains(remoteJudge))
        val crop = WrestlingCropPlanner.plan(image, selected)
        assertTrue(crop.right < 1000)
        assertTrue(crop.left <= primary.left)
        assertTrue(crop.right >= other.right)
    }
}
