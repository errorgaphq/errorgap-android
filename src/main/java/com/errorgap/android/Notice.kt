package com.errorgap.android

data class NoticeOptions(
    val context: Map<String, Any?>? = null,
    val environment: Map<String, Any?>? = null,
    val session: Map<String, Any?>? = null,
    val params: Map<String, Any?>? = null,
    /** Who was using the app; sent as `session.user_id` / `session.user_email`. */
    val userId: String? = null,
    val userEmail: String? = null,
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
        config.rootDirectory?.let { defaultContext["root_directory"] = it }
        defaultContext.putAll(Filter.clean(options.context))

        val defaultEnvironment = mutableMapOf<String, Any?>()
        if (config.deviceInfo.isNotEmpty()) defaultEnvironment.putAll(config.deviceInfo)
        defaultEnvironment.putAll(Filter.clean(options.environment))

        val errorEntry = mapOf(
            "type" to throwable.javaClass.simpleName.ifEmpty { throwable.javaClass.name },
            "message" to Filter.string(throwable.message ?: "", 8192),
            "backtrace" to Backtrace.fromThrowable(
                throwable,
                config.rootDirectory,
                config.inAppPackages,
            ),
        )

        val notice = linkedMapOf<String, Any?>(
            "received_at" to isoTimestamp(),
            "errors" to listOf(errorEntry),
            "context" to defaultContext,
            "environment" to defaultEnvironment,
            "session" to Filter.params(userSession(options) + (options.session ?: emptyMap()), config.filterKeys),
            "params" to Filter.params(options.params, config.filterKeys),
        )
        config.projectId?.let { notice["project_id"] = it }
        return notice
    }

    private fun userSession(options: NoticeOptions): Map<String, Any?> {
        val user = LinkedHashMap<String, Any?>()
        options.userId?.takeIf { it.isNotBlank() }?.let { user["user_id"] = it }
        options.userEmail?.takeIf { it.isNotBlank() }?.let { user["user_email"] = it }
        return user
    }
}
