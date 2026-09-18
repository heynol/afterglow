package dev.heynol.afterglow.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionGovernorTest {

    /** Runs [seconds] one-second windows against a model of fps per scale; returns the level history. */
    private fun run(governor: ResolutionGovernor, seconds: Int, target: Float = 60f, model: (Float) -> Float): List<Int> {
        val history = ArrayList<Int>()
        for (s in 1..seconds) {
            governor.onWindow(model(governor.scale), target, s * 1_000_000_000L)
            history += governor.index
        }
        return history
    }

    @Test
    fun compositorLimitClimbsToFullResolution() {
        // ~50 fps whatever the visual costs, as on the TV where everything is composed at 4K.
        val g = ResolutionGovernor()
        val history = run(g, 60) { 50f }
        assertEquals(ResolutionGovernor.LEVELS.lastIndex, history.last())
    }

    @Test
    fun gpuLimitSettlesWhereFramesAreOnTime() {
        // Frame rate falls with pixel count; 60 fps up to scale 0.59.
        val g = ResolutionGovernor()
        val history = run(g, 120) { scale -> (60f * (0.59f * 0.59f) / (scale * scale)).coerceAtMost(60f) }
        // Brief probes of the next level are allowed now and then; the rest of the time it stays put.
        val settled = history.takeLast(60).map { ResolutionGovernor.LEVELS[it] }
        assertTrue("settled at $settled", settled.count { it == 0.59f } >= 55)
    }

    @Test
    fun mixedLimitStopsWhereLoweringStopsHelping() {
        // GPU-bound above 0.5, compositor-bound (50 fps) below.
        val g = ResolutionGovernor()
        val history = run(g, 90) { scale -> (50f * (0.5f * 0.5f) / (scale * scale)).coerceAtMost(50f) }
        val settled = history.takeLast(30).map { ResolutionGovernor.LEVELS[it] }
        assertTrue("settled at $settled", settled.all { it == 0.5f })
    }

    @Test
    fun farBelowTargetStepsDownEveryWindow() {
        // Whatever the neighbours measured, 20 of 60 fps has to come down fast.
        val g = ResolutionGovernor()
        // One window per step, and one skipped window after each change while the buffer resizes.
        run(g, 9) { 20f }
        assertEquals(0, g.index)
    }

    @Test
    fun noiseDoesNotCauseOscillation() {
        val g = ResolutionGovernor()
        var n = 0
        val history = run(g, 120) { 50f + if (n++ % 2 == 0) 1.5f else -1.5f }
        val changes = history.takeLast(60).zipWithNext().count { (a, b) -> a != b }
        assertTrue("changes: $changes", changes <= 2)
    }
}
