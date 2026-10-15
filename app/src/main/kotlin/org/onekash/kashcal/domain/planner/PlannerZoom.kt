package org.onekash.kashcal.domain.planner

const val MIN_PLANNER_ZOOM = 0.85f
const val MAX_PLANNER_ZOOM = 1.6f

/** Clamps a Planner zoom to [MIN_PLANNER_ZOOM]..[MAX_PLANNER_ZOOM]; NaN, infinite or <= 0 is 1. */
fun clampPlannerZoom(value: Float): Float =
    if (value.isNaN() || value.isInfinite() || value <= 0f) 1f else value.coerceIn(MIN_PLANNER_ZOOM, MAX_PLANNER_ZOOM)

/**
 * Accumulates one pinch gesture's zoom from its start value, so each frame multiplies a value
 * this gesture owns rather than state that lags a frame behind. Every frame's change is applied,
 * however small, so a slow pinch adds up.
 */
class PinchZoomAccumulator(seed: Float) {
    private var value = clampPlannerZoom(seed)

    /** Applies one frame's zoom change and returns the clamped zoom. */
    fun apply(zoomChange: Float): Float {
        value = clampPlannerZoom(value * zoomChange)
        return value
    }
}
