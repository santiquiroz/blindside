package io.github.santiquiroz.blindside.core.tracking

data class Assignment(val trackIndexByDetection: List<Int?>, val cost: Double)

// Exact GNN by enumeration: at most 3 detections per LD2450 frame, so the search space stays tiny.
fun assignExact(detectionCount: Int, trackCount: Int, newTrackCost: Double, cost: (detection: Int, track: Int) -> Double?): Assignment =
    search(0, emptySet(), detectionCount, trackCount, newTrackCost, cost)

private fun search(
    detection: Int,
    used: Set<Int>,
    detectionCount: Int,
    trackCount: Int,
    newTrackCost: Double,
    cost: (Int, Int) -> Double?,
): Assignment {
    if (detection == detectionCount) return Assignment(emptyList(), 0.0)
    val options = (0 until trackCount).filter { it !in used }.mapNotNull { track ->
        cost(detection, track)?.let { c -> prepend(track, c, search(detection + 1, used + track, detectionCount, trackCount, newTrackCost, cost)) }
    }
    val unassigned = prepend(null, newTrackCost, search(detection + 1, used, detectionCount, trackCount, newTrackCost, cost))
    return (options + unassigned).minBy { it.cost }
}

private fun prepend(track: Int?, cost: Double, rest: Assignment) =
    Assignment(listOf(track) + rest.trackIndexByDetection, cost + rest.cost)
