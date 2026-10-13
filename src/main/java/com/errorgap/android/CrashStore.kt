package com.errorgap.android

import java.io.File

/**
 * Keeps a crash on disk until the server has it. A crashing app is usually
 * gone before a network request can finish (and may not make one at all from
 * its main thread), so the notice is written first and sent on the next
 * launch if it did not get out.
 */
internal class CrashStore(directory: String) {
    private val dir = File(directory, "errorgap-crashes")

    /** Write one notice; returns its file, or null when the disk refused. */
    fun write(json: String): File? = try {
        dir.mkdirs()
        prune()
        val file = File(dir, "crash-${System.currentTimeMillis()}-${System.nanoTime()}.json")
        file.writeText(json, Charsets.UTF_8)
        file
    } catch (_: Throwable) {
        null
    }

    /** Stored notices, oldest first. */
    fun pending(): List<File> = try {
        dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    fun remove(file: File) {
        runCatching { file.delete() }
    }

    /** A crash loop must not fill the disk: keep the newest few. */
    private fun prune() {
        val files = pending()
        if (files.size >= MAX_STORED) files.take(files.size - MAX_STORED + 1).forEach { remove(it) }
    }

    private companion object {
        const val MAX_STORED = 10
    }
}
