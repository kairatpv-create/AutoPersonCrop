package kz.autopersoncrop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WrestlingCropPlannerTest {
    private val image = ImageSize(1280, 720)

    @Test
    fun portraitLeavesFivePercentAboveAndBelowPersonNotSource() {
        val person = RectD(160.0, 80.0, 430.0, 650.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(person))
        assertEquals(48, crop.top)
        assertEquals(682, crop.bottom)
        assertTrue(crop.left < person.left)
        assertTrue(crop.right > person.right)
        assertTrue("portrait must stay compact", crop.width < 380)
        val topEmptyFraction = (person.top - crop.top) / crop.height.toDouble()
        val bottomEmptyFraction = (crop.bottom - person.bottom) / crop.height.toDouble()
        assertTrue(abs(topEmptyFraction - 0.05) < 0.006)
        assertTrue(abs(bottomEmptyFraction - 0.05) < 0.006)
    }

    @Test
    fun landscapeLeavesFivePercentLeftAndRightOfPeopleNotSource() {
        val lying = RectD(140.0, 250.0, 1100.0, 520.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(lying))
        assertEquals(86, crop.left)
        assertEquals(1154, crop.right)
        assertTrue(crop.top > 0)
        assertTrue(crop.bottom < image.height)
        assertTrue(crop.top < lying.top)
        assertTrue(crop.bottom > lying.bottom)
        val leftEmptyFraction = (lying.left - crop.left) / crop.width.toDouble()
        val rightEmptyFraction = (crop.right - lying.right) / crop.width.toDouble()
        assertTrue(abs(leftEmptyFraction - 0.05) < 0.006)
        assertTrue(abs(rightEmptyFraction - 0.05) < 0.006)
    }

    @Test
    fun landscapeNearLeftEdgeUsesLessMarginThereAndBreathesOnRight() {
        val lyingAtLeft = RectD(5.0, 220.0, 940.0, 520.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(lyingAtLeft))
        assertEquals(0, crop.left)
        assertTrue(crop.right > lyingAtLeft.right)
        assertTrue(crop.height < image.height)
        val leftEmpty = lyingAtLeft.left - crop.left
        val rightEmpty = crop.right - lyingAtLeft.right
        assertTrue("blocked edge may have less than 5%", leftEmpty < rightEmpty)
        assertTrue("opposite side must keep natural breathing room", rightEmpty > 50.0)
    }

    @Test
    fun landscapeNearRightEdgeMirrorsNaturalAsymmetry() {
        val lyingAtRight = RectD(330.0, 205.0, 1274.0, 515.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(lyingAtRight))
        assertEquals(1280, crop.right)
        assertTrue(crop.left < lyingAtRight.left)
        val leftEmpty = lyingAtRight.left - crop.left
        val rightEmpty = crop.right - lyingAtRight.right
        assertTrue(rightEmpty < leftEmpty)
        assertTrue(leftEmpty > 50.0)
    }

    @Test
    fun portraitNearTopEdgeUsesAvailableTopAndAddsBreathingRoomBelow() {
        val person = RectD(430.0, 4.0, 690.0, 610.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(person))
        assertEquals(0, crop.top)
        assertTrue(crop.bottom > person.bottom)
        val topEmpty = person.top - crop.top
        val bottomEmpty = crop.bottom - person.bottom
        assertTrue(topEmpty < bottomEmpty)
        assertTrue(bottomEmpty > 35.0)
    }

    @Test
    fun portraitNearBottomEdgeUsesAvailableBottomAndAddsBreathingRoomAbove() {
        val person = RectD(400.0, 115.0, 675.0, 716.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(person))
        assertEquals(720, crop.bottom)
        assertTrue(crop.top < person.top)
        val topEmpty = person.top - crop.top
        val bottomEmpty = crop.bottom - person.bottom
        assertTrue(bottomEmpty < topEmpty)
        assertTrue(topEmpty > 35.0)
    }

    @Test
    fun portraitPersonNearLeftEdgeIsNotArtificiallyCentered() {
        val person = RectD(7.0, 90.0, 270.0, 655.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(person))
        assertEquals(0, crop.left)
        assertTrue(crop.right > person.right)
        val subjectFraction = (person.centerX - crop.left) / crop.width.toDouble()
        assertTrue("edge subject must remain visually left-biased", subjectFraction < 0.47)
    }

    @Test
    fun twoStandingWrestlersStayPortraitAndBothFit() {
        val first = RectD(250.0, 90.0, 500.0, 660.0)
        val second = RectD(520.0, 100.0, 780.0, 650.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(first, second))
        assertEquals(58, crop.top)
        assertEquals(692, crop.bottom)
        assertTrue(crop.width < crop.height)
        assertTrue(crop.left < first.left)
        assertTrue(crop.right > second.right)
    }

    @Test
    fun widelySeparatedStandingPeopleStillUsePortraitFivePercentAxis() {
        val first = RectD(80.0, 90.0, 290.0, 650.0)
        val second = RectD(900.0, 95.0, 1110.0, 645.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(first, second))
        assertEquals(58, crop.top)
        assertEquals(682, crop.bottom)
        val topEmptyFraction = (90.0 - crop.top) / crop.height.toDouble()
        val bottomEmptyFraction = (crop.bottom - 650.0) / crop.height.toDouble()
        assertTrue(abs(topEmptyFraction - 0.05) < 0.008)
        assertTrue(abs(bottomEmptyFraction - 0.05) < 0.008)
        assertTrue(crop.left < first.left)
        assertTrue(crop.right > second.right)
    }

    @Test
    fun lyingEvidenceForTwoPeopleProducesLandscapeEnvelope() {
        val first = RectD(120.0, 260.0, 690.0, 510.0)
        val second = RectD(560.0, 220.0, 1130.0, 500.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(first, second))
        assertTrue(crop.width > crop.height)
        assertTrue(crop.left < first.left)
        assertTrue(crop.right > second.right)
        assertTrue(crop.top < second.top)
        assertTrue(crop.bottom > first.bottom)
    }

    @Test
    fun softShapeGuardDoesNotLockLandscapeToExactTwoToOne() {
        val lying = RectD(200.0, 300.0, 1080.0, 500.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(lying))
        val ratio = crop.width.toDouble() / crop.height.toDouble()
        assertTrue(crop.width > crop.height)
        assertTrue("shape guide must not force exact 2:1", abs(ratio - 2.0) > 0.08)
        assertTrue(crop.top < lying.top)
        assertTrue(crop.bottom > lying.bottom)
    }

    @Test
    fun secondaryAxisHasEnoughSafetyForHandsAndShoes() {
        val person = RectD(420.0, 90.0, 650.0, 650.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(person))
        val leftSpace = person.left - crop.left
        val rightSpace = crop.right - person.right
        assertTrue("left side should not hug a limb", leftSpace >= person.width * 0.06)
        assertTrue("right side should not hug a limb", rightSpace >= person.width * 0.06)
    }

    @Test
    fun fullWidthLandscapeDoesNotForceFullSourceHeight() {
        val wideAction = RectD(0.0, 210.0, 1280.0, 520.0)
        val crop = WrestlingCropPlanner.plan(image, listOf(wideAction))
        assertEquals(0, crop.left)
        assertEquals(1280, crop.right)
        assertTrue("wide action must still crop top/bottom", crop.height < image.height)
        assertTrue(crop.top <= wideAction.top)
        assertTrue(crop.bottom >= wideAction.bottom)
    }
}
