package kz.autopersoncrop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WrestlingSubjectSelectorTest {
    private val image = ImageSize(1280, 720)

    @Test
    fun giantFrameLikeBoxCannotOverrideRealPerson() {
        val giant = RectD(20.0, 10.0, 1260.0, 710.0)
        val real = RectD(220.0, 80.0, 520.0, 660.0)
        val selected = WrestlingSubjectSelector.select(image, listOf(giant, real))
        assertEquals(listOf(real), selected)
    }

    @Test
    fun onePersonStaysOnePerson() {
        val real = RectD(220.0, 80.0, 520.0, 660.0)
        assertEquals(listOf(real), WrestlingSubjectSelector.select(image, listOf(real)))
    }

    @Test
    fun twoPlausibleWrestlersStayTwo() {
        val first = RectD(240.0, 100.0, 520.0, 650.0)
        val second = RectD(500.0, 120.0, 790.0, 650.0)
        assertEquals(listOf(first, second), WrestlingSubjectSelector.select(image, listOf(first, second)))
    }

    @Test
    fun closerActualPartnerBeatsEarlierRefereeObservation() {
        val first = RectD(245.0, 95.0, 515.0, 660.0)
        val referee = RectD(780.0, 110.0, 1030.0, 650.0)
        val second = RectD(490.0, 115.0, 770.0, 655.0)
        val selected = WrestlingSubjectSelector.select(image, listOf(first, referee, second))
        assertEquals(listOf(first, second), selected)
    }

    @Test
    fun tallRefereeFirstCannotStretchGroundWrestlersToFullHeight() {
        val referee = RectD(70.0, 0.0, 340.0, 700.0)
        val lowerFirst = RectD(475.0, 420.0, 840.0, 685.0)
        val lowerSecond = RectD(610.0, 405.0, 1010.0, 680.0)
        val selected = WrestlingSubjectSelector.select(image, listOf(referee, lowerFirst, lowerSecond))
        assertEquals(2, selected.size)
        assertTrue(selected.contains(lowerFirst))
        assertTrue(selected.contains(lowerSecond))
        val crop = WrestlingCropPlanner.plan(image, selected)
        assertTrue("the empty gym above the wrestlers must be removed", crop.top > 250)
        assertTrue("a referee must not make the crop nearly full height", crop.height < 450)
    }

    @Test
    fun singleStandingNearUpperEdgeIsNotDiscardedAsReferee() {
        val real = RectD(460.0, 3.0, 660.0, 675.0)
        assertEquals(listOf(real), WrestlingSubjectSelector.select(image, listOf(real)))
    }

    @Test
    fun standingAndLyingFightersWithStrongContactAreKeptTogether() {
        val upper = RectD(460.0, 2.0, 740.0, 660.0)
        val lower = RectD(590.0, 420.0, 1100.0, 705.0)
        val selected = WrestlingSubjectSelector.select(image, listOf(upper, lower))
        assertTrue(selected.contains(upper))
        assertTrue(selected.contains(lower))
    }

    @Test
    fun failureMarkerRaisesInsteadOfBecomingFullFrameCrop() {
        var thrown = false
        try {
            WrestlingSubjectSelector.select(image, listOf(RectD(-10.0, -10.0, -9.0, -9.0)))
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun negativeMarkerIsIgnoredIfLaterRecoveryFoundRealPerson() {
        val real = RectD(200.0, 90.0, 520.0, 660.0)
        val selected = WrestlingSubjectSelector.select(image, listOf(RectD(-10.0, -10.0, -9.0, -9.0), real))
        assertEquals(listOf(real), selected)
    }
}
