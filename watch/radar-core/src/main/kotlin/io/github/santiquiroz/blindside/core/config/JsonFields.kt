package io.github.santiquiroz.blindside.core.config

internal class Field<P>(
    val name: String,
    val write: (P) -> String,
    val read: (P, Any) -> P,
)

internal class FieldSet<P> {
    fun double(name: String, get: (P) -> Double, set: (P, Double) -> P): Field<P> =
        Field<P>(name, { get(it).toString() }) { p, v -> (v as? Double)?.let { set(p, it) } ?: p }

    fun long(name: String, get: (P) -> Long, set: (P, Long) -> P): Field<P> =
        Field<P>(name, { get(it).toString() }) { p, v -> (v as? Double)?.let { set(p, it.toLong()) } ?: p }

    fun int(name: String, get: (P) -> Int, set: (P, Int) -> P): Field<P> =
        Field<P>(name, { get(it).toString() }) { p, v -> (v as? Double)?.let { set(p, it.toInt()) } ?: p }

    fun boolean(name: String, get: (P) -> Boolean, set: (P, Boolean) -> P): Field<P> =
        Field<P>(name, { get(it).toString() }) { p, v -> (v as? Boolean)?.let { set(p, it) } ?: p }

    fun intSet(name: String, get: (P) -> Set<Int>, set: (P, Set<Int>) -> P): Field<P> =
        Field<P>(name, { get(it).sorted().joinToString(",", "[", "]") }) { p, v ->
            (v as? List<*>)?.let { items -> set(p, items.mapNotNull { (it as? Double)?.toInt() }.toSet()) } ?: p
        }
}

internal fun <P> fieldsOf(block: FieldSet<P>.() -> List<Field<P>>): List<Field<P>> = FieldSet<P>().block()
