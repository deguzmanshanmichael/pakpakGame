package com.shan.pakpak.json

/**
 * A small multiplatform JSON implementation with the same API shape as org.json
 * (which only exists on Android), so game code reads the same on every platform.
 * Integers are stored as Long, other numbers as Double, null as [JSONObject.NULL].
 */
class JSONObject() {
    private val map = LinkedHashMap<String, Any>()

    constructor(text: String) : this() {
        val v = Parser(text).parseValue()
        require(v is JSONObject) { "JSON text is not an object" }
        map.putAll(v.map)
    }

    constructor(source: Map<*, *>) : this() {
        for ((k, v) in source) if (k != null) map[k.toString()] = wrap(v)
    }

    fun put(key: String, value: Any?): JSONObject { map[key] = wrap(value); return this }
    fun has(key: String) = map.containsKey(key)
    fun isNull(key: String) = map[key].let { it == null || it === NULL }
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun length() = map.size
    fun remove(key: String): Any? = map.remove(key)

    fun opt(key: String): Any? = map[key]?.takeIf { it !== NULL }
    fun get(key: String): Any = opt(key) ?: throw JSONException("No value for $key")

    fun getString(key: String): String = get(key).let { if (it is String) it else it.toString() }
    fun getInt(key: String): Int = toNum(get(key), key).toInt()
    fun getLong(key: String): Long = toLong(get(key), key)
    fun getDouble(key: String): Double = toNum(get(key), key).toDouble()
    fun getBoolean(key: String): Boolean = get(key).let { it as? Boolean ?: (it as? String)?.toBooleanStrictOrNull() ?: throw JSONException("$key is not a boolean") }
    fun getJSONObject(key: String): JSONObject = get(key) as? JSONObject ?: throw JSONException("$key is not an object")
    fun getJSONArray(key: String): JSONArray = get(key) as? JSONArray ?: throw JSONException("$key is not an array")

    fun optString(key: String, fallback: String = ""): String = opt(key)?.let { if (it is String) it else it.toString() } ?: fallback
    fun optInt(key: String, fallback: Int = 0): Int = opt(key)?.let { numOrNull(it)?.toInt() } ?: fallback
    fun optLong(key: String, fallback: Long = 0L): Long = opt(key)?.let { runCatching { toLong(it, key) }.getOrNull() } ?: fallback
    fun optDouble(key: String, fallback: Double = Double.NaN): Double = opt(key)?.let { numOrNull(it)?.toDouble() } ?: fallback
    fun optBoolean(key: String, fallback: Boolean = false): Boolean = opt(key)?.let { it as? Boolean ?: (it as? String)?.toBooleanStrictOrNull() } ?: fallback
    fun optJSONObject(key: String): JSONObject? = opt(key) as? JSONObject
    fun optJSONArray(key: String): JSONArray? = opt(key) as? JSONArray

    override fun toString(): String = StringBuilder().also { write(this, it) }.toString()

    companion object {
        /** Explicit JSON null (distinct from a missing key). */
        val NULL: Any = object { override fun toString() = "null" }
    }
}

class JSONArray() {
    private val list = ArrayList<Any>()

    constructor(text: String) : this() {
        val v = Parser(text).parseValue()
        require(v is JSONArray) { "JSON text is not an array" }
        list.addAll(v.list)
    }

    constructor(source: Collection<*>) : this() { for (v in source) list.add(wrap(v)) }

    fun length() = list.size
    fun put(value: Any?): JSONArray { list.add(wrap(value)); return this }
    fun get(i: Int): Any = list[i].takeIf { it !== JSONObject.NULL } ?: throw JSONException("null at $i")
    fun getString(i: Int): String = get(i).let { if (it is String) it else it.toString() }
    fun getInt(i: Int): Int = toNum(get(i), "[$i]").toInt()
    fun getLong(i: Int): Long = toLong(get(i), "[$i]")
    fun getDouble(i: Int): Double = toNum(get(i), "[$i]").toDouble()
    fun getJSONObject(i: Int): JSONObject = get(i) as? JSONObject ?: throw JSONException("[$i] is not an object")
    fun getJSONArray(i: Int): JSONArray = get(i) as? JSONArray ?: throw JSONException("[$i] is not an array")

    override fun toString(): String = StringBuilder().also { write(this, it) }.toString()

    internal val items: List<Any> get() = list
}

class JSONException(message: String) : Exception(message)

private fun wrap(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is JSONObject, is JSONArray, is String, is Boolean, is Long, is Double -> v
    is Int -> v.toLong()
    is Short -> v.toLong()
    is Byte -> v.toLong()
    is Float -> v.toDouble()
    is Map<*, *> -> JSONObject(v)
    is Collection<*> -> JSONArray(v)
    is IntArray -> JSONArray(v.toList())
    else -> if (v === JSONObject.NULL) v else v.toString()
}

private fun numOrNull(v: Any): Number? = when (v) {
    is Long -> v
    is Double -> v
    is String -> v.toLongOrNull() ?: v.toDoubleOrNull()
    else -> null
}

private fun toNum(v: Any, key: String): Number = numOrNull(v) ?: throw JSONException("$key is not a number")
private fun toLong(v: Any, key: String): Long = when (val n = toNum(v, key)) { is Long -> n; else -> n.toDouble().toLong() }

private fun write(v: Any, sb: StringBuilder) {
    when (v) {
        is JSONObject -> {
            sb.append('{')
            var first = true
            for (k in v.keys()) {
                if (!first) sb.append(',')
                first = false
                quote(k, sb); sb.append(':'); write(v.opt(k) ?: JSONObject.NULL, sb)
            }
            sb.append('}')
        }
        is JSONArray -> {
            sb.append('[')
            v.items.forEachIndexed { i, e -> if (i > 0) sb.append(','); write(e, sb) }
            sb.append(']')
        }
        is String -> quote(v, sb)
        is Double -> sb.append(if (v.isFinite()) (if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()) else "null")
        else -> sb.append(v.toString())
    }
}

private fun quote(s: String, sb: StringBuilder) {
    sb.append('"')
    for (c in s) when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        '\b' -> sb.append("\\b")
        '\u000C' -> sb.append("\\f")
        else -> if (c < ' ') { sb.append("\\u"); sb.append(c.code.toString(16).padStart(4, '0')) } else sb.append(c)
    }
    sb.append('"')
}

private class Parser(private val s: String) {
    private var i = 0

    fun parseValue(): Any {
        skipWs()
        if (i >= s.length) throw JSONException("Unexpected end of JSON")
        return when (val c = s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", JSONObject.NULL)
            else -> if (c == '-' || c.isDigit()) parseNumber() else throw JSONException("Unexpected '$c' at $i")
        }
    }

    private fun literal(word: String, value: Any): Any {
        if (!s.startsWith(word, i)) throw JSONException("Bad literal at $i")
        i += word.length
        return value
    }

    private fun parseObject(): JSONObject {
        val o = JSONObject()
        i++
        skipWs()
        if (peek() == '}') { i++; return o }
        while (true) {
            skipWs()
            if (peek() != '"') throw JSONException("Expected key at $i")
            val k = parseString()
            skipWs()
            if (peek() != ':') throw JSONException("Expected ':' at $i")
            i++
            o.put(k, parseValue())
            skipWs()
            when (peek()) {
                ',' -> i++
                '}' -> { i++; return o }
                else -> throw JSONException("Expected ',' or '}' at $i")
            }
        }
    }

    private fun parseArray(): JSONArray {
        val a = JSONArray()
        i++
        skipWs()
        if (peek() == ']') { i++; return a }
        while (true) {
            a.put(parseValue())
            skipWs()
            when (peek()) {
                ',' -> i++
                ']' -> { i++; return a }
                else -> throw JSONException("Expected ',' or ']' at $i")
            }
        }
    }

    private fun parseString(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) throw JSONException("Unterminated string")
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    when (val e = s[i++]) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                        'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> throw JSONException("Bad escape \\$e")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): Any {
        val start = i
        if (peek() == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
        val t = s.substring(start, i)
        return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
    }

    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'
    private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
}
