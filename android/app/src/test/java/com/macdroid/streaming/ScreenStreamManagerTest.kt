package com.macdroid.streaming

import android.util.DisplayMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStreamManagerTest {

    @Test
    fun testComputeTargetDimensions_portraitDevice() {
        val metrics = DisplayMetrics().apply {
            widthPixels = 1080
            heightPixels = 2400
        }

        val dimensions = ScreenStreamManager.computeTargetDimensions(metrics, maxDimension = 1280)
        assertEquals(1280, dimensions.height)
        // Aspect ratio is 1080 / 2400 = 0.45; 1280 * 0.45 = 576
        assertEquals(576, dimensions.width)
        assertTrue(dimensions.width % 2 == 0)
        assertTrue(dimensions.height % 2 == 0)
    }

    @Test
    fun testComputeTargetDimensions_landscapeDevice() {
        val metrics = DisplayMetrics().apply {
            widthPixels = 1920
            heightPixels = 1080
        }

        val dimensions = ScreenStreamManager.computeTargetDimensions(metrics, maxDimension = 1280)
        assertEquals(1280, dimensions.width)
        // Aspect ratio is 1080 / 1920 = 0.5625; 1280 * 0.5625 = 720
        assertEquals(720, dimensions.height)
        assertTrue(dimensions.width % 2 == 0)
        assertTrue(dimensions.height % 2 == 0)
    }

    @Test
    fun testComputeTargetDimensions_smallerThanMax() {
        val metrics = DisplayMetrics().apply {
            widthPixels = 640
            heightPixels = 480
        }

        val dimensions = ScreenStreamManager.computeTargetDimensions(metrics, maxDimension = 1280)
        assertEquals(640, dimensions.width)
        assertEquals(480, dimensions.height)
    }

    @Test
    fun testComputeTargetDimensions_invalidMetricsFallback() {
        val metrics = DisplayMetrics().apply {
            widthPixels = 0
            heightPixels = 0
        }

        val dimensions = ScreenStreamManager.computeTargetDimensions(metrics, maxDimension = 1280)
        assertEquals(960, dimensions.width)
        assertEquals(540, dimensions.height)
    }
}
