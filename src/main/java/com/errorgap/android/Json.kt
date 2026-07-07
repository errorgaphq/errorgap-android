package com.errorgap.android

/**
 * Minimal JSON encoder for the notice envelope. Avoids a runtime
 * dependency on Gson or Moshi so the library has a tiny footprint.
 */
internal object Json {
    fun encode(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value)
            is Number -> sb.append(value)
            is CharSequence -> writeString(sb, value.toString())
            is Map<*, *> -> writeMap(sb, value)
            is Iterable<*> -> writeList(sb, value)
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeMap(sb: StringBuilder, map: Map<*, *>) {
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            writeString(sb, k.toString())
            sb.append(':')
            write(sb, v)
        }
        sb.append('}')
    }

    private fun writeList(sb: StringBuilder, list: Iterable<*>) {
        sb.append('[')
        var first = true
        for (item in list) {
            if (!first) sb.append(',')
            first = false
            write(sb, item)
        }
        sb.append(']')
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> {
                    if (c.code < 0x20) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
    }
}
