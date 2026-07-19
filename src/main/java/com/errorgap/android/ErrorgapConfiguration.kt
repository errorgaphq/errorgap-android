package com.errorgap.android

data class ErrorgapConfiguration(
    val endpoint: String = System.getenv("ERRORGAP_ENDPOINT") ?: "http://127.0.0.1:3030",
    val projectSlug: String? = System.getenv("ERRORGAP_PROJECT_SLUG"),
    val projectId: String? = System.getenv("ERRORGAP_PROJECT_ID"),
    val apiKey: String? = System.getenv("ERRORGAP_API_KEY"),
    val environment: String = System.getenv("ERRORGAP_ENVIRONMENT") ?: "production",
    val release: String? = null,
    val rootDirectory: String? = System.getProperty("user.dir"),
    val inAppPackages: List<String> = emptyList(),
    val async: Boolean = true,
    val filterKeys: List<String> = DEFAULT_FILTER_KEYS,
    val timeoutMs: Int = 5_000,
    val queueSize: Int = 100,
    val apmEnabled: Boolean = envBoolean("ERRORGAP_APM_ENABLED", false),
    val apmSampleRate: Double = envDouble("ERRORGAP_APM_SAMPLE_RATE", 1.0)
        .coerceIn(0.0, 1.0),
    val logsEnabled: Boolean = envBoolean("ERRORGAP_LOGS_ENABLED", false),
    val minimumLogLevel: String = System.getenv("ERRORGAP_MINIMUM_LOG_LEVEL") ?: "warn",
    /**
     * Pre-captured device fingerprint. The library doesn't reach into
     * `android.os.Build` directly so plain JVM tests work; callers running
     * on Android should pass `DeviceInfo.fromAndroid(context)` here.
     */
    val deviceInfo: Map<String, Any?> = emptyMap(),
) {
    fun validate() {
        require(!projectSlug.isNullOrBlank()) { "Errorgap projectSlug is required" }
        require(endpoint.isNotBlank()) { "Errorgap endpoint is required" }
        require(queueSize > 0) { "Errorgap queueSize must be positive" }
        require(timeoutMs > 0) { "Errorgap timeoutMs must be positive" }
    }

    companion object {
        val DEFAULT_FILTER_KEYS = listOf(
            "password", "password_confirmation", "token", "secret",
            "api_key", "authorization", "cookie",
        )

        private fun envBoolean(key: String, fallback: Boolean): Boolean =
            System.getenv(key)?.trim()?.lowercase()?.let {
                when (it) {
                    "1", "true", "yes", "on" -> true
                    "0", "false", "no", "off" -> false
                    else -> fallback
                }
            } ?: fallback

        private fun envDouble(key: String, fallback: Double): Double =
            System.getenv(key)?.toDoubleOrNull() ?: fallback
    }
}
