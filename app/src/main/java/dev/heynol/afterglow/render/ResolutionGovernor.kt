package dev.heynol.afterglow.render

/**
 * Picks the render scale from frame rates measured at each resolution level.
 *
 * Lowering the resolution only pays off when the visual's own rendering is what drops frames. On TVs
 * the compositor often sets the limit instead: every layer, video included, is composed on the GPU at
 * panel resolution, so frames come late even when a visual draws almost nothing. A controller that
 * reacts to late frames alone then sinks to the lowest resolution for no gain. This one remembers the
 * frame rate at each level and only steps down while that buys frames, and back up when it didn't.
 *
 * Feed it one measurement window at a time ([onWindow], about a second each). Not thread-safe.
 */
class ResolutionGovernor(
    private val levels: FloatArray = LEVELS,
    private val startIndex: Int = DEFAULT_START,
) {
    private val fps = FloatArray(levels.size)
    private val measuredAt = LongArray(levels.size)
    private var cleanWindows = 0
    private var skipNext = true

    var index = startIndex
        private set

    /** Visuals whose picture depends on the buffer (feedback trails) change level far less eagerly. */
    var conservative = false

    val scale: Float get() = levels[index]

    /** Forget all measurements, e.g. when the visual or the output size changes; optionally jump to [level]. */
    fun reset(level: Int = index) {
        index = level.coerceIn(0, levels.size - 1)
        fps.fill(0f)
        measuredAt.fill(0L)
        cleanWindows = 0
        skipNext = true
    }

    /**
     * Records one window measured at the current level, [measured] frames per second against a
     * [target] rate. Returns true if the level changed.
     */
    fun onWindow(measured: Float, target: Float, nowNanos: Long): Boolean {
        // The first window after a change includes the buffer reallocation.
        if (skipNext) {
            skipNext = false
            return false
        }
        val i = index
        fps[i] = if (known(i, nowNanos)) fps[i] * 0.5f + measured * 0.5f else measured
        measuredAt[i] = nowNanos
        val current = fps[i]
        val onTarget = current >= target * ON_TARGET

        if (onTarget) {
            cleanWindows++
            val up = i + 1
            if (up < levels.size) {
                val upKnown = known(up, nowNanos)
                // A level that failed earlier gets another chance after a longer clean stretch: conditions
                // change (the video underneath pauses, a transition ends).
                var wait = if (!upKnown || fps[up] >= target * ON_TARGET) PROBE_UP_WINDOWS else RETRY_UP_WINDOWS
                if (conservative) wait *= 4
                if (cleanWindows >= wait) {
                    return move(up)
                }
            }
            return false
        }

        cleanWindows = 0
        val up = i + 1
        val down = i - 1
        // Far below the target: step down without weighing anything up. Whatever else is going on, this
        // resolution is not working, and the frame rate matters more than the sharpness here.
        if (current < target * FLOOR && down >= 0) return move(down)
        // Did the level above run about as well? Then this one bought nothing: go back up.
        if (up < levels.size && known(up, nowNanos) && fps[up] >= current * (1f - TOLERANCE / 2f)) return move(up)
        // Try a lower level, unless it is already known not to help.
        if (down >= 0 && (!known(down, nowNanos) || fps[down] > current * (1f + TOLERANCE))) return move(down)
        // Resolution isn't what limits the frame rate here: see whether a higher one costs nothing.
        if (up < levels.size && !known(up, nowNanos)) return move(up)
        return false
    }

    override fun toString() = levels.indices.joinToString(" ", "levels[") { i ->
        (if (i == index) "*" else "") + (if (measuredAt[i] != 0L) "%.0f".format(fps[i]) else "-")
    } + "]"

    private fun known(level: Int, nowNanos: Long) = measuredAt[level] != 0L && nowNanos - measuredAt[level] < MEMORY_NANOS

    private fun move(to: Int): Boolean {
        index = to
        cleanWindows = 0
        skipNext = true
        return true
    }

    companion object {
        val LEVELS = floatArrayOf(0.35f, 0.42f, 0.5f, 0.59f, 0.7f, 0.8f, 0.9f, 1f)
        const val DEFAULT_START = 4

        /** At least this share of the target counts as on target (56 of 60 fps). */
        const val ON_TARGET = 0.93f

        /** Frame-rate differences within this share are noise. */
        const val TOLERANCE = 0.06f
        /** Below this share of the target, step down at once. */
        const val FLOOR = 0.75f
        const val PROBE_UP_WINDOWS = 3
        const val RETRY_UP_WINDOWS = 45
        const val MEMORY_NANOS = 90_000_000_000L
    }
}
