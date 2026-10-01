package io.github.santiquiroz.blindside.shared.bridge

// MiniJson reads every number as Double; epoch milliseconds stay exact below 2^53.
internal fun longField(fields: Map<*, *>, key: String): Long? = (fields[key] as? Double)?.toLong()
