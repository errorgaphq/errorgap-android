package com.errorgap.android

object Backtrace {
    fun fromThrowable(throwable: Throwable): List<Map<String, Any?>> {
        val frames = mutableListOf<Map<String, Any?>>()
        var index = 0
        var current: Throwable? = throwable
        while (current != null) {
            for (element in current.stackTrace) {
                frames += mapOf(
                    "file" to (classToFile(element.className)),
                    "line" to (if (element.lineNumber > 0) element.lineNumber else null),
                    "function" to "${element.className}.${element.methodName}",
                    "in_app" to isInApp(element.className),
                    "index" to index++,
                )
            }
            current = current.cause
        }
        return frames
    }

    private fun classToFile(className: String): String =
        className.replace('.', '/') + ".kt"

    private fun isInApp(className: String): Boolean {
        if (className.startsWith("java.") ||
            className.startsWith("javax.") ||
            className.startsWith("kotlin.") ||
            className.startsWith("kotlinx.") ||
            className.startsWith("android.") ||
            className.startsWith("androidx.") ||
            className.startsWith("dalvik.") ||
            className.startsWith("com.android.") ||
            className.startsWith("com.sun.") ||
            className.startsWith("org.junit.")
        ) return false
        return true
    }
}
