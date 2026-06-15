package io.errorgap.android

import java.time.Instant

data class NoticeOptions(
    val context: Map<String, Any?>? = null,
    val environment: Map<String, Any?>? = null,
    val session: Map<String, Any?>? = null,
    val params: Map<String, Any?>? = null,
)

object Notice {
    private const val NOTIFIER_ID = "errorgap-android"

    fun build(
        throwable: Throwable,
        config: ErrorgapConfiguration,
        options: NoticeOptions = NoticeOptions(),
    ): Map<String, Any?> {
        val defaultContext = mutableMapOf<String, Any?>(
            "notifier" to NOTIFIER_ID,
            "notifier_version" to Version.CURRENT,
            "environment" to config.environment,
        )
        config.release?.let { defaultContext["release"] = it }
        options.context?.let { defaultContext.putAll(it) }

        val defaultEnvironment = mutableMapOf<String, Any?>()
        if (config.deviceInfo.isNotEmpty()) defaultEnvironment.putAll(config.deviceInfo)
        options.environment?.let { defaultEnvironment.putAll(it) }

        val errorEntry = mapOf(
            "type" to throwable.javaClass.simpleName.ifEmpty { throwable.javaClass.name },
            "message" to (throwable.message ?: ""),
            "backtrace" to Backtrace.fromThrowable(throwable),
        )

        val notice = linkedMapOf<String, Any?>(
            "received_at" to Instant.now().toString(),
            "errors" to listOf(errorEntry),
            "context" to defaultContext,
            "environment" to defaultEnvironment,
            "session" to (options.session ?: emptyMap<String, Any?>()),
            "params" to Filter.params(options.params, config.filterKeys),
        )
        config.projectId?.let { notice["project_id"] = it }
        return notice
    }
}
