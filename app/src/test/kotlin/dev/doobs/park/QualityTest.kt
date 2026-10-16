package dev.doobs.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTest {

    @Test
    fun `under ten metres is excellent`() {
        assertEquals(Quality.EXCELLENT, Quality.of(1f))
        assertEquals(Quality.EXCELLENT, Quality.of(4f)) // typical open-sky fix
        assertEquals(Quality.EXCELLENT, Quality.of(9.99f))
    }

    @Test
    fun `ten to twenty five metres is acceptable`() {
        assertEquals(Quality.ACCEPTABLE, Quality.of(10f))
        assertEquals(Quality.ACCEPTABLE, Quality.of(12f)) // our best indoor fix
        assertEquals(Quality.ACCEPTABLE, Quality.of(25f))
    }

    @Test
    fun `beyond twenty five metres is too rough to save`() {
        assertEquals(Quality.TOO_ROUGH, Quality.of(25.01f))
        assertEquals(Quality.TOO_ROUGH, Quality.of(81f))  // seen indoors
        assertEquals(Quality.TOO_ROUGH, Quality.of(100f)) // a cell-tower guess
    }

    @Test
    fun `a fix with no accuracy cannot be certified`() {
        // Better to refuse than to assume a nameless fix is good.
        assertEquals(Quality.TOO_ROUGH, Quality.of(null))
        assertEquals(Quality.TOO_ROUGH, Quality.of(0f))
        assertEquals(Quality.TOO_ROUGH, Quality.of(-1f))
    }

    @Test
    fun `only the rough tier is unusable`() {
        assertTrue(Quality.EXCELLENT.usable)
        assertTrue(Quality.ACCEPTABLE.usable)
        assertFalse(Quality.TOO_ROUGH.usable)
    }
}
