package io.github.santiquiroz.blindside.core.protocol

// radar-core has no runtime dependencies, so the few JSON documents it touches (belt info, config) use this reader.
object MiniJson {
    fun parse(text: String): Any? {
        val parsed = readValue(text, skipSpaces(text, 0))
        val end = skipSpaces(text, parsed.next)
        require(end == text.length) { "unexpected '${text[end]}' at $end" }
        return parsed.value
    }

    fun parseOrNull(text: String): Any? = runCatching { parse(text) }.getOrNull()

    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { append(escaped(it)) }
        append('"')
    }

    fun obj(fields: List<Pair<String, String>>): String =
        fields.joinToString(",", "{", "}") { (key, value) -> "${quote(key)}:$value" }

    fun array(items: List<String>): String = items.joinToString(",", "[", "]")
}

private data class Parsed(val value: Any?, val next: Int)

private fun readValue(text: String, at: Int): Parsed {
    require(at < text.length) { "unexpected end of JSON" }
    return when (text[at]) {
        '{' -> readObject(text, skipSpaces(text, at + 1), emptyMap())
        '[' -> readArray(text, skipSpaces(text, at + 1), emptyList())
        '"' -> readString(text, at + 1, StringBuilder())
        't' -> readLiteral(text, at, "true", true)
        'f' -> readLiteral(text, at, "false", false)
        'n' -> readLiteral(text, at, "null", null)
        else -> readNumber(text, at)
    }
}

private tailrec fun readObject(text: String, at: Int, fields: Map<String, Any?>): Parsed {
    if (text.getOrNull(at) == '}') return Parsed(fields, at + 1)
    require(text.getOrNull(at) == '"') { "expected a key at $at" }
    val key = readString(text, at + 1, StringBuilder())
    val colon = skipSpaces(text, key.next)
    require(text.getOrNull(colon) == ':') { "expected ':' at $colon" }
    val value = readValue(text, skipSpaces(text, colon + 1))
    val next = skipSpaces(text, value.next)
    val updated = fields + (key.value as String to value.value)
    return when (text.getOrNull(next)) {
        ',' -> readObject(text, skipSpaces(text, next + 1), updated)
        '}' -> Parsed(updated, next + 1)
        else -> throw IllegalArgumentException("expected ',' or '}' at $next")
    }
}

private tailrec fun readArray(text: String, at: Int, items: List<Any?>): Parsed {
    if (text.getOrNull(at) == ']') return Parsed(items, at + 1)
    val value = readValue(text, at)
    val next = skipSpaces(text, value.next)
    return when (text.getOrNull(next)) {
        ',' -> readArray(text, skipSpaces(text, next + 1), items + value.value)
        ']' -> Parsed(items + value.value, next + 1)
        else -> throw IllegalArgumentException("expected ',' or ']' at $next")
    }
}

private tailrec fun readString(text: String, at: Int, out: StringBuilder): Parsed {
    require(at < text.length) { "unterminated string" }
    return when (val c = text[at]) {
        '"' -> Parsed(out.toString(), at + 1)
        '\\' -> readString(text, at + escapeLength(text, at), out.append(unescape(text, at)))
        else -> readString(text, at + 1, out.append(c))
    }
}

private fun escapeLength(text: String, at: Int): Int = if (text.getOrNull(at + 1) == 'u') 6 else 2

private fun unescape(text: String, at: Int): Char = when (val code = text.getOrNull(at + 1)) {
    '"', '\\', '/' -> code
    'b' -> '\b'
    'f' -> '\u000C'
    'n' -> '\n'
    'r' -> '\r'
    't' -> '\t'
    'u' -> text.substring(at + 2, at + 6).toInt(16).toChar()
    else -> throw IllegalArgumentException("bad escape at $at")
}

private fun readLiteral(text: String, at: Int, word: String, value: Any?): Parsed {
    require(text.startsWith(word, at)) { "expected $word at $at" }
    return Parsed(value, at + word.length)
}

private fun readNumber(text: String, at: Int): Parsed {
    val end = (at until text.length).firstOrNull { text[it] !in NUMBER_CHARS } ?: text.length
    val number = text.substring(at, end).toDoubleOrNull() ?: throw IllegalArgumentException("bad number at $at")
    return Parsed(number, end)
}

private tailrec fun skipSpaces(text: String, at: Int): Int =
    if (at < text.length && text[at].isWhitespace()) skipSpaces(text, at + 1) else at

private fun escaped(c: Char): String = when {
    c == '"' -> "\\\""
    c == '\\' -> "\\\\"
    c == '\n' -> "\\n"
    c == '\r' -> "\\r"
    c == '\t' -> "\\t"
    c < ' ' -> "\\u%04x".format(c.code)
    else -> c.toString()
}

private const val NUMBER_CHARS = "+-0123456789.eE"
