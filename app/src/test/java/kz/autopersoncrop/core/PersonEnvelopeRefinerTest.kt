package kz.autopersoncrop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonEnvelopeRefinerTest {
    @Test
    fun reflectedFootCannotStretchAnAlreadyRecognizedPair() {
        val upper = RectD(464.0, 26.0, 703.0, 421.0)
        val lower = RectD(447.0, 301.0, 741.0, 649.0)
        val inflated = RectD(445.0, 18.0, 744.0, 717.0)
        val refined = PersonEnvelopeRefiner.refine(inflated, listOf(upper, lower))
        assertTrue(refined.bottom >= lower.bottom)
        assertTrue("floor must not become person geometry", refined.bottom < 656.0)
        assertTrue(refined.top <= upper.top)
        assertTrue(refined.left <= lower.left && refined.right >= lower.right)
    }

    @Test
    fun aNewLyingBodyMayExtendFarBeyondTheStandingDetection() {
        val standing = RectD(375.0, 147.0, 657.0, 701.0)
        val recoveredAction = RectD(135.0, 138.0, 858.0, 717.0)
        assertEquals(recoveredAction, PersonEnvelopeRefiner.refine(recoveredAction, listOf(standing)))
    }

    @Test
    fun recoveryWithoutWholeFrameEvidenceRemainsUsable() {
        val recovered = RectD(130.0, 420.0, 1020.0, 698.0)
        assertEquals(recovered, PersonEnvelopeRefiner.refine(recovered, emptyList()))
    }

    @Test
    fun trustedExtremitiesSurviveASmallerRecoveryEstimate() {
        val trusted = RectD(100.0, 90.0, 580.0, 650.0)
        val recovered = RectD(105.0, 96.0, 576.0, 641.0)
        assertEquals(trusted, PersonEnvelopeRefiner.refine(recovered, listOf(trusted)))
    }
}
