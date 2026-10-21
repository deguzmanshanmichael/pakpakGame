package com.shan.pakpak.util

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.uuid.Uuid

fun newId(): String = Uuid.random().toString()

/** Fixed-decimal formatting without java.lang.String.format. */
fun fmt(v: Double, decimals: Int): String {
    var p = 1L
    repeat(decimals) { p *= 10 }
    val scaled = (abs(v) * p).roundToLong()
    val whole = scaled / p
    val frac = (scaled % p).toString().padStart(decimals, '0')
    val sign = if (v < 0 && scaled != 0L) "-" else ""
    return if (decimals == 0) "$sign$whole" else "$sign$whole.$frac"
}

fun fmt(v: Float, decimals: Int): String = fmt(v.toDouble(), decimals)

/** Percent-encodes a query parameter value (UTF-8). */
fun urlEncode(s: String): String {
    val sb = StringBuilder()
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch.isLetterOrDigit() && c < 128 || ch in "-_.~") sb.append(ch)
        else { sb.append('%'); sb.append(c.toString(16).uppercase().padStart(2, '0')) }
    }
    return sb.toString()
}

/** "yyyy-MM-dd" for a UTC timestamp (civil-from-days, no platform date APIs needed). */
fun utcDay(epochMs: Long): String {
    val days = Math.floorDiv(epochMs, 86_400_000L)
    val z = days + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    var y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    if (m <= 2) y++
    return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

private object Math {
    fun floorDiv(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q }
}
