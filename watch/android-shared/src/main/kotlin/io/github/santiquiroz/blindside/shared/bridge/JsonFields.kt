package io.github.santiquiroz.blindside.shared.bridge

// MiniJson reads every number as Double; epoch milliseconds stay exact below 2^53.
internal fun longField(fields: Map<*, *>, key: String): Long? = (fields[key] as? Double)?.toLong()

// A fractional id or sign is malformed, not a number to round.
internal fun wholeField(fields: Map<*, *>, key: String): Long? =
    (fields[key] as? Double)?.takeIf { it % 1.0 == 0.0 }?.toLong()
