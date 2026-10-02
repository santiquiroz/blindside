package io.github.santiquiroz.blindside.shared.tactical

enum class TacticalKind { BASE, SPAWN, OBJECTIVE }

fun nextTacticalKind(current: TacticalKind): TacticalKind =
    TacticalKind.entries[(current.ordinal + 1) % TacticalKind.entries.size]

fun withTacticalPoint(points: Map<TacticalKind, GeoPoint>, kind: TacticalKind, at: GeoPoint): Map<TacticalKind, GeoPoint> =
    points + (kind to at)
