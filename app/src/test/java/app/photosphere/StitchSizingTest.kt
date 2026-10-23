package app.photosphere

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StitchSizingTest {
    @Test fun rotatedExifDimensionsAndFieldOfViewHaveIdenticalSampling() {
        val portrait = StitchSampling(3000, 4000, 60.0, 75.0)
        val landscape = StitchSampling(4000, 3000, 75.0, 60.0)
        val a = StitchSizing.choose(listOf(portrait), StitchSizing.MAX_BUDGET)
        val b = StitchSizing.choose(listOf(landscape), StitchSizing.MAX_BUDGET)
        assertEquals(a, b)
        assertTrue(a.full > a.quick)
        assertEquals(8192, a.quick)
    }

    @Test fun lowerResolutionSourceConstrainsOutputWithoutUpscaling() {
        val large = StitchSampling(3000, 4000, 60.0, 75.0)
        val small = StitchSampling(500, 700, 90.0, 110.0)
        val mixed = StitchSizing.choose(listOf(large, small), StitchSizing.MAX_BUDGET)
        val smallOnly = StitchSizing.choose(listOf(small), StitchSizing.MAX_BUDGET)
        assertEquals(smallOnly.full, mixed.full)
        assertTrue(mixed.full <= 2.0 * Math.PI * 250.0)
        assertEquals(0, mixed.full % 256)
    }

    @Test fun memoryLimitIncludesLargestFullResolutionDecodeAndWorkingReserve() {
        val image = StitchSampling(6000, 8000, 60.0, 75.0)
        val budget = 600L * StitchSizing.MIB
        val sizes = StitchSizing.choose(listOf(image), budget)
        assertTrue(sizes.full < sizes.sourceWidth)
        assertTrue(StitchSizing.estimatedPeak(sizes.full, sizes.largestSourceBytes) <= budget)
        assertTrue(StitchSizing.estimatedPeak(sizes.full + 256, sizes.largestSourceBytes) > budget)
    }

    @Test fun fullWidthNeverExceedsSupportedNativeCeiling() {
        val sizes = StitchSizing.choose(listOf(StitchSampling(8000, 10_000, 30.0, 35.0)), 4L * 1024 * StitchSizing.MIB)
        assertEquals(20_480, sizes.full)
        assertEquals(8192, sizes.quick)
    }

    @Test(expected = IllegalArgumentException::class)
    fun impossibleMemoryBudgetFailsBeforeAllocatingCanvas() {
        StitchSizing.choose(listOf(StitchSampling(6000, 8000, 60.0, 75.0)), 100L * StitchSizing.MIB)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tinySourcesAreNotUpscaledToMinimumWidth() {
        StitchSizing.choose(listOf(StitchSampling(32, 32, 90.0, 90.0)), StitchSizing.MAX_BUDGET)
    }
}
