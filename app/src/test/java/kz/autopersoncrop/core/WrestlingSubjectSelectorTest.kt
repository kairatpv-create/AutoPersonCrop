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
        assertEquals(real, selected[0])
        assertEquals(real, selected[1])
    }

    @Test
    fun onePersonIsDuplicatedOnlyToBlockOldSequenceAssist() {
        val real = RectD(220.0, 80.0, 520.0, 660.0)
        assertEquals(listOf(real, real), WrestlingSubjectSelector.select(image, listOf(real)))
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
        assertEquals(real, selected[0])
    }
}
