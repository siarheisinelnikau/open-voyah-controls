package org.openvoyah.controls.data.routines

import org.json.JSONArray
import org.openvoyah.controls.domain.routines.RoutineStep

internal fun encodeRoutineSteps(steps: List<RoutineStep>) = JSONArray(steps.map(RoutineStep::key))

internal fun decodeRoutineSteps(items: JSONArray): List<RoutineStep> {
    require(items.length() <= RoutineStep.LIMIT)
    return List(items.length()) { index ->
        val key = items.get(index)
        requireNotNull(RoutineStep.entries.firstOrNull { it.key == key }) { "Unknown routine step" }
    }
}
