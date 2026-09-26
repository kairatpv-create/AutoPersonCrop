package kz.autopersoncrop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WrestlingCropPlannerTest {
    private val image = ImageSize(1280, 720)

    @Test
    fun portraitTrimsTopBottomAndKeepsOffCenterPosition() {
        val person = RectD(160.0, 80.0, 430.0, 650.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(person, person))

        assertEquals(36, crop.top)
        assertEquals(684, crop.bottom)
        assertTrue(crop.left < person.left)
        assertTrue(crop.right > person.right)

        val personFractionInsideCrop = (person.centerX - crop.left) / crop.width.toDouble()
        assertTrue("person must not be forced to the center", personFractionInsideCrop < 0.40)
    }

    @Test
    fun landscapeTrimsFivePercentFromBothSidesAndUsesNaturalHeight() {
        val lying = RectD(140.0, 250.0, 1100.0, 520.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(lying, lying))

        assertEquals(64, crop.left)
        assertEquals(1216, crop.right)
        assertTrue(crop.top > 0)
        assertTrue(crop.bottom < image.height)
        assertTrue(crop.top < lying.top)
        assertTrue(crop.bottom > lying.bottom)
    }

    @Test
    fun personAtSourceEdgeIsNeverCutForFivePercentRule() {
        val lyingAtLeft = RectD(5.0, 220.0, 940.0, 520.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(lyingAtLeft, lyingAtLeft))

        assertEquals(0, crop.left)
        assertTrue(crop.right > lyingAtLeft.right)
    }

    @Test
    fun twoStandingWrestlersStayPortrait() {
        val first = RectD(250.0, 90.0, 500.0, 660.0)
        val second = RectD(520.0, 100.0, 780.0, 650.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(first, second))

        assertEquals(36, crop.top)
        assertEquals(684, crop.bottom)
        assertTrue(crop.width < crop.height)
        assertTrue(crop.left < first.left)
        assertTrue(crop.right > second.right)
    }
}
