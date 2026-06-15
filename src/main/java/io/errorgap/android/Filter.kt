package io.errorgap.android

internal object Filter {
    const val FILTERED = "[FILTERED]"

    fun params(input: Map<String, Any?>?, filterKeys: List<String>): Map<String, Any?> {
        if (input.isNullOrEmpty()) return emptyMap()
        val lowered = filterKeys.map { it.lowercase() }
        return walk(input, lowered)
    }

    private fun walk(input: Map<String, Any?>, lowered: List<String>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(input.size)
        for ((key, value) in input) {
            when {
                isSensitive(key, lowered) -> out[key] = FILTERED
                value is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    out[key] = walk(value as Map<String, Any?>, lowered)
                }
                else -> out[key] = value
            }
        }
        return out
    }

    private fun isSensitive(key: String, lowered: List<String>): Boolean {
        val lk = key.lowercase()
        return lowered.any { lk.contains(it) }
    }
}
