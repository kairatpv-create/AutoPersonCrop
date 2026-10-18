package kz.autopersoncrop.core

import kotlin.math.max
import kotlin.math.min

/** Reconcile recovery views with independently detected whole-frame anatomy. */
object PersonEnvelopeRefiner {
    fun refine(recovery: RectD, wholeFrame: List<RectD>): RectD {
        if (wholeFrame.isEmpty()) return recovery
        val trusted = RectD(
            wholeFrame.minOf { it.left }, wholeFrame.minOf { it.top },
            wholeFrame.maxOf { it.right }, wholeFrame.maxOf { it.bottom },
        )
        val intersection = max(0.0, min(trusted.right, recovery.right) - max(trusted.left, recovery.left)) *
            max(0.0, min(trusted.bottom, recovery.bottom) - max(trusted.top, recovery.top))
        // A substantially new body/limb envelope must remain eligible: lying
        // people can be entirely absent from the unrotated whole-frame pass.
        if (intersection / recovery.area.coerceAtLeast(1.0) < 0.78) return recovery

        // Near-equivalent views should not add a reflected foot, mat or floor.
        // Retain the trusted envelope plus a small edge-estimation allowance.
        // The planner supplies the final photographic margins exactly once.
        val dx = trusted.width * 0.01
        val dy = trusted.height * 0.01
        return RectD(
            min(trusted.left, max(recovery.left, trusted.left - dx)),
            min(trusted.top, max(recovery.top, trusted.top - dy)),
            max(trusted.right, min(recovery.right, trusted.right + dx)),
            max(trusted.bottom, min(recovery.bottom, trusted.bottom + dy)),
        )
    }
}
