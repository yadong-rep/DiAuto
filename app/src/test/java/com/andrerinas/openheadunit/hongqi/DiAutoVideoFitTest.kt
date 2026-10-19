package com.andrerinas.openheadunit.hongqi

import org.junit.Assert.assertEquals
import org.junit.Test

class DiAutoVideoFitTest {
    @Test fun wideVideoIsLetterboxedWithoutCropping() {
        val scale=DiAutoVideoHub.fit(1920,720,960,720)
        assertEquals(1f,scale.first,0.0001f)
        assertEquals(0.5f,scale.second,0.0001f)
    }
    @Test fun tallVideoIsPillarboxedWithoutCropping() {
        val scale=DiAutoVideoHub.fit(1280,720,1920,720)
        assertEquals(2f/3f,scale.first,0.0001f)
        assertEquals(1f,scale.second,0.0001f)
    }
    @Test fun matchingAspectUsesWholeOutput() {
        assertEquals(1f to 1f,DiAutoVideoHub.fit(1280,720,1920,1080))
    }
}
