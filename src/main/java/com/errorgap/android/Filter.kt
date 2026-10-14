package com.errorgap.android

internal object Filter {
    const val FILTERED = "[FILTERED]"
    const val TRUNCATED = "[TRUNCATED]"
    private const val MAX_STRING = 4096
    private const val MAX_ITEMS = 128
    private const val MAX_DEPTH = 8

    /**
     * Mask sensitive keys at any depth (inside lists too) and cut long
     * strings and large or deeply nested collections.
     */
    fun params(input: Map<String, Any?>?, filterKeys: List<String>): Map<String, Any?> {
        if (input.isNullOrEmpty()) return emptyMap()
        return walkMap(input, filterKeys.map { it.lowercase() }, 0)
    }

    /** Truncate without masking anything. */
    fun clean(input: Map<String, Any?>?): Map<String, Any?> {
        if (input.isNullOrEmpty()) return emptyMap()
        return walkMap(input, null, 0)
    }

    fun string(value: String, limit: Int = MAX_STRING): String =
        if (value.length > limit) value.take(limit) + TRUNCATED else value

    private fun walkMap(input: Map<*, *>, lowered: List<String>?, depth: Int): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((rawKey, value) in input) {
            if (out.size >= MAX_ITEMS) break
            val key = rawKey?.toString() ?: "null"
            out[key] = if (lowered != null && isSensitive(key, lowered)) FILTERED else walk(value, lowered, depth + 1)
        }
        return out
    }

    private fun walk(value: Any?, lowered: List<String>?, depth: Int): Any? = when (value) {
        null, is Boolean, is Int, is Long -> value
        is Double -> if (value.isNaN() || value.isInfinite()) value.toString() else value
        is Float -> if (value.isNaN() || value.isInfinite()) value.toString() else value
        is Number -> value
        is String -> string(value)
        is Map<*, *> -> if (depth >= MAX_DEPTH) TRUNCATED else walkMap(value, lowered, depth)
        is Iterable<*> ->
            if (depth >= MAX_DEPTH) TRUNCATED else value.take(MAX_ITEMS).map { walk(it, lowered, depth + 1) }
        is Array<*> ->
            if (depth >= MAX_DEPTH) TRUNCATED else value.take(MAX_ITEMS).map { walk(it, lowered, depth + 1) }
        else -> string(runCatching { value.toString() }.getOrDefault("[UNPRINTABLE]"))
    }

    private fun isSensitive(key: String, lowered: List<String>): Boolean {
        val lk = key.lowercase()
        return lowered.any { it.isNotEmpty() && lk.contains(it) }
    }
}
