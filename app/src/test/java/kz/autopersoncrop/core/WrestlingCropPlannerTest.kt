package kz.autopersoncrop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WrestlingCropPlannerTest {
    private val image = ImageSize(1280, 720)

    @Test
    fun portraitCutsBothSideBackgroundsIndependently() {
        val person = RectD(160.0, 80.0, 430.0, 650.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(person))

        assertEquals(48, crop.top)
        assertEquals(682, crop.bottom)
        assertEquals(151, crop.left)
        assertEquals(439, crop.right)
        assertTrue(crop.left > 0)
        assertTrue(crop.right < image.width)

        val topFraction = (person.top - crop.top) / crop.height.toDouble()
        val bottomFraction = (crop.bottom - person.bottom) / crop.height.toDouble()
        assertTrue(abs(topFraction - 0.05) < 0.006)
        assertTrue(abs(bottomFraction - 0.05) < 0.006)
    }

    @Test
    fun landscapeCutsTopAndBottomBackgroundsIndependently() {
        val person = RectD(140.0, 250.0, 1100.0, 520.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(person))

        assertEquals(86, crop.left)
        assertEquals(1154, crop.right)
        assertEquals(241, crop.top)
        assertEquals(529, crop.bottom)
        assertTrue(crop.top > 0)
        assertTrue(crop.bottom < image.height)
    }

    @Test
    fun blockedLeftMarginIsNotTransferredToRight() {
        val lying = RectD(5.0, 220.0, 940.0, 520.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(lying))

        assertEquals(0, crop.left)
        assertEquals(992, crop.right)
        val rightEmpty = crop.right - lying.right
        assertTrue(rightEmpty in 51.0..53.0)
    }

    @Test
    fun blockedRightMarginIsNotTransferredToLeft() {
        val lying = RectD(330.0, 205.0, 1274.0, 515.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(lying))

        assertEquals(1280, crop.right)
        assertEquals(277, crop.left)
        val leftEmpty = lying.left - crop.left
        assertTrue(leftEmpty in 52.0..54.0)
    }

    @Test
    fun blockedTopMarginIsNotTransferredToBottom() {
        val person = RectD(430.0, 4.0, 690.0, 610.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(person))

        assertEquals(0, crop.top)
        assertEquals(644, crop.bottom)
        assertTrue(crop.bottom - person.bottom in 33.0..35.0)
    }

    @Test
    fun blockedBottomMarginIsNotTransferredToTop() {
        val person = RectD(400.0, 115.0, 675.0, 716.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(person))

        assertEquals(720, crop.bottom)
        assertEquals(81, crop.top)
        assertTrue(person.top - crop.top in 33.0..35.0)
    }

    @Test
    fun portraitNearLeftEdgeStillCutsRightSide() {
        val person = RectD(7.0, 90.0, 270.0, 655.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(person))

        assertEquals(0, crop.left)
        assertEquals(279, crop.right)
        assertTrue(crop.right < 300)
    }

    @Test
    fun twoStandingPeopleKeepBothAndCutOutsideEmptySpace() {
        val first = RectD(250.0, 90.0, 500.0, 660.0)
        val second = RectD(520.0, 100.0, 780.0, 650.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(first, second))

        assertEquals(58, crop.top)
        assertEquals(692, crop.bottom)
        assertEquals(233, crop.left)
        assertEquals(797, crop.right)
    }

    @Test
    fun widelySeparatedStandingPeopleAreStillBothKept() {
        val first = RectD(80.0, 90.0, 290.0, 650.0)
        val second = RectD(900.0, 95.0, 1110.0, 645.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(first, second))

        assertEquals(58, crop.top)
        assertEquals(682, crop.bottom)
        assertEquals(47, crop.left)
        assertEquals(1143, crop.right)
    }

    @Test
    fun lyingPairUsesTightLandscapeEnvelope() {
        val first = RectD(120.0, 260.0, 690.0, 510.0)
        val second = RectD(560.0, 220.0, 1130.0, 500.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(first, second))

        assertEquals(63, crop.left)
        assertEquals(1187, crop.right)
        assertEquals(210, crop.top)
        assertEquals(520, crop.bottom)
    }

    @Test
    fun edgeDetectorFragmentCannotKeepWholeRightSide() {
        val primaryPerson = RectD(420.0, 90.0, 650.0, 650.0)
        val weakHorizontalEdgeFragment = RectD(620.0, 250.0, 1278.0, 390.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(primaryPerson, weakHorizontalEdgeFragment))

        assertTrue("right-side detector fragment must be rejected", crop.right < 700)
        assertTrue(crop.left > 390)
    }

    @Test
    fun realSecondPersonAtRightEdgeIsNotDiscarded() {
        val first = RectD(420.0, 90.0, 650.0, 650.0)
        val second = RectD(980.0, 100.0, 1275.0, 645.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(first, second))

        assertEquals(1280, crop.right)
        assertTrue(crop.left < first.left)
        assertTrue(crop.top < first.top)
        assertTrue(crop.bottom > first.bottom)
    }

    @Test
    fun noAspectRatioAddsBackground() {
        val lying = RectD(200.0, 300.0, 1080.0, 500.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(lying))
        val ratio = crop.width.toDouble() / crop.height.toDouble()

        assertTrue(ratio > 4.0)
        assertEquals(151, crop.left)
        assertEquals(1129, crop.right)
        assertEquals(293, crop.top)
        assertEquals(507, crop.bottom)
    }

    @Test
    fun fullWidthActionStillCutsTopAndBottom() {
        val action = RectD(0.0, 210.0, 1280.0, 520.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(action))

        assertEquals(0, crop.left)
        assertEquals(1280, crop.right)
        assertEquals(200, crop.top)
        assertEquals(530, crop.bottom)
    }
}
