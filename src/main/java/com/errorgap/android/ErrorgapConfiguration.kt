package com.errorgap.android

data class ErrorgapConfiguration(
    val endpoint: String = System.getenv("ERRORGAP_ENDPOINT") ?: "http://127.0.0.1:3030",
    val projectSlug: String? = System.getenv("ERRORGAP_PROJECT_SLUG"),
    val projectId: String? = System.getenv("ERRORGAP_PROJECT_ID"),
    val apiKey: String? = System.getenv("ERRORGAP_API_KEY"),
    val environment: String = System.getenv("ERRORGAP_ENVIRONMENT") ?: "production",
    val release: String? = null,
    val async: Boolean = true,
    val filterKeys: List<String> = DEFAULT_FILTER_KEYS,
    val timeoutMs: Int = 5_000,
    val queueSize: Int = 100,
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
    }

    companion object {
        val DEFAULT_FILTER_KEYS = listOf(
            "password", "password_confirmation", "token", "secret",
            "api_key", "authorization", "cookie",
        )
    }
}
