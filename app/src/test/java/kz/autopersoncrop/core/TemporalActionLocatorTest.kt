package kz.autopersoncrop.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalActionLocatorTest {
    private fun frame(rect: PixelRect? = null, whole: Boolean = false): MotionFrame {
        val w = 96
        val h = 54
        val pixels = IntArray(w * h) { 0xff353535.toInt() }
        if (whole) pixels.fill(0xffeeeeee.toInt())
        if (rect != null) {
            for (y in rect.top until rect.bottom) for (x in rect.left until rect.right)
                pixels[y * w + x] = 0xffe0b0a0.toInt()
        }
        return MotionFrame(w, h, pixels)
    }

    @Test
    fun motionWithinSportsActionLocatesRegionWithoutFilenameAssumptions() {
        val current = frame(PixelRect(30, 20, 65, 44))
        val prev = frame(PixelRect(23, 20, 58, 44))
        val next = frame(PixelRect(35, 20, 70, 44))
        val focus = TemporalActionLocator.locate(current, listOf(prev, next))
        assertNotNull(focus)
        assertTrue(focus!!.supportingFrames == 2)
        assertTrue(focus.rect.left < 38 && focus.rect.right > 61)
        assertTrue(focus.rect.top <= 24 && focus.rect.bottom >= 39)
        assertTrue(focus.rect.area < 96 * 54 * 0.70)
    }

    @Test
    fun globalSceneChangeIsNotMistakenForOneWrestler() {
        assertNull(TemporalActionLocator.locate(frame(), listOf(frame(whole = true))))
    }

    @Test
    fun staticFramesDoNotInventMotion() {
        val a = frame(PixelRect(30, 20, 65, 44))
        assertNull(TemporalActionLocator.locate(a, listOf(a, a)))
    }

    @Test
    fun mismatchedImageDimensionsAreIgnored() {
        val a = frame()
        val b = MotionFrame(54, 96, IntArray(96 * 54))
        assertNull(TemporalActionLocator.locate(a, listOf(b)))
    }
}
