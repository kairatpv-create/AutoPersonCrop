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
