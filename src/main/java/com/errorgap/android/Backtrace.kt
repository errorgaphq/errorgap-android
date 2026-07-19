package com.errorgap.android

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

object Backtrace {
    private const val CONTEXT_RADIUS = 6
    private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024L
    private const val MAX_LINE_LENGTH = 400

    @JvmOverloads
    fun fromThrowable(
        throwable: Throwable,
        rootDirectory: String? = null,
        inAppPackages: List<String> = emptyList(),
    ): List<Map<String, Any?>> {
        val frames = mutableListOf<Map<String, Any?>>()
        var index = 0
        var current: Throwable? = throwable
        while (current != null) {
            for (element in current.stackTrace) {
                val file = sourcePath(element)
                val line = if (element.lineNumber > 0) element.lineNumber else null
                val frame = linkedMapOf<String, Any?>(
                    "file" to file,
                    "line" to line,
                    "function" to "${element.className}.${element.methodName}",
                    "in_app" to isInApp(element.className, inAppPackages),
                    "index" to index++,
                )
                if (line != null) {
                    sourceFor(element, file, line, rootDirectory)?.let { frame["source"] = it }
                }
                frames += frame
            }
            current = current.cause
        }
        return frames
    }

    private fun sourcePath(element: StackTraceElement): String {
        val className = element.className.substringBefore('$')
        val packageName = className.substringBeforeLast('.', "")
        val fileName = element.fileName
            ?: className.substringAfterLast('.') + if (className.endsWith("Kt")) ".kt" else ".java"
        return if (packageName.isEmpty()) fileName else packageName.replace('.', '/') + "/" + fileName
    }

    private fun isInApp(className: String, inAppPackages: List<String>): Boolean {
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
        return inAppPackages.isEmpty() || inAppPackages.any {
            className == it || className.startsWith("$it.")
        }
    }

    private fun sourceFor(
        element: StackTraceElement,
        sourcePath: String,
        targetLine: Int,
        rootDirectory: String?,
    ): Map<String, Any?>? {
        val lines = sourceFromRoot(sourcePath, rootDirectory)
            ?: sourceFromClassPath(element.className, sourcePath)
            ?: return null
        if (lines.isEmpty()) return null

        val targetIndex = (targetLine - 1).coerceIn(0, lines.lastIndex)
        val startIndex = (targetIndex - CONTEXT_RADIUS).coerceAtLeast(0)
        val endIndex = (targetIndex + CONTEXT_RADIUS + 1).coerceAtMost(lines.size)
        return linkedMapOf(
            "start_line" to startIndex + 1,
            "lines" to lines.subList(startIndex, endIndex).map {
                if (it.length > MAX_LINE_LENGTH) it.substring(0, MAX_LINE_LENGTH) else it
            },
        )
    }

    private fun sourceFromRoot(sourcePath: String, rootDirectory: String?): List<String>? {
        if (rootDirectory.isNullOrBlank()) return null
        val root = File(rootDirectory)
        val candidates = listOf(
            File(root, "src/main/kotlin/$sourcePath"),
            File(root, "src/main/java/$sourcePath"),
            File(root, "src/test/kotlin/$sourcePath"),
            File(root, "src/test/java/$sourcePath"),
            File(root, sourcePath),
        )
        return candidates.firstNotNullOfOrNull(::readFile)
    }

    private fun sourceFromClassPath(className: String, sourcePath: String): List<String>? {
        val loader = Thread.currentThread().contextClassLoader ?: Backtrace::class.java.classLoader
        try {
            loader?.getResourceAsStream(sourcePath)?.use { return readStream(it) }
        } catch (_: Exception) {
            // Fall through to a sibling JVM source JAR when present.
        }

        return try {
            val frameClass = Class.forName(className, false, loader)
            val location = frameClass.protectionDomain?.codeSource?.location ?: return null
            val binary = File(location.toURI())
            if (!binary.isFile || binary.extension != "jar") return null
            val sourceJar = File(binary.parentFile, "${binary.nameWithoutExtension}-sources.jar")
            if (!sourceJar.isFile || sourceJar.length() > 100L * 1024 * 1024) return null
            ZipFile(sourceJar).use { zip ->
                val entry = zip.getEntry(sourcePath) ?: return null
                if (entry.size > MAX_SOURCE_BYTES) return null
                zip.getInputStream(entry).use(::readStream)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readFile(file: File): List<String>? = try {
        if (!file.isFile || file.length() > MAX_SOURCE_BYTES) null else file.readLines(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private fun readStream(input: InputStream): List<String>? {
        val bytes = input.readBytes()
        if (bytes.size > MAX_SOURCE_BYTES) return null
        return bytes.toString(Charsets.UTF_8).split(Regex("\\R"))
    }
}
