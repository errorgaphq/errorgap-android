package com.errorgap.android

object Errorgap {
    @Volatile private var client: ErrorgapClient? = null
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val lock = Any()
    @Volatile private var captureGlobalsInstalled = false

    @JvmStatic
    @JvmOverloads
    fun init(configuration: ErrorgapConfiguration, captureGlobals: Boolean = true) {
        synchronized(lock) {
            configuration.validate()
            client = ErrorgapClient(configuration)
            if (captureGlobals) installHandler() else uninstallHandler()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun notify(throwable: Throwable, options: NoticeOptions = NoticeOptions()): DeliveryResult {
        val c = client
        return c?.notify(throwable, options)
            ?: DeliveryResult(error = IllegalStateException("Errorgap not initialized"))
    }

    @JvmStatic
    @JvmOverloads
    fun notifyTransaction(
        transaction: ApmTransaction,
        sync: Boolean = false,
    ): DeliveryResult = client?.notifyTransaction(transaction, sync)
        ?: DeliveryResult(error = IllegalStateException("Errorgap not initialized"))

    @JvmStatic
    @JvmOverloads
    fun notifyLog(
        message: String,
        level: String = "info",
        source: String? = null,
        sync: Boolean = false,
    ): DeliveryResult = client?.notifyLog(message, level, source, sync)
        ?: DeliveryResult(error = IllegalStateException("Errorgap not initialized"))

    @JvmStatic
    @JvmOverloads
    fun <T> trackJob(
        jobClass: String,
        queue: String = "default",
        operation: (SpanCollector) -> T,
    ): T {
        val activeClient = client ?: throw IllegalStateException("Errorgap not initialized")
        return activeClient.trackJob(jobClass, queue, operation)
    }

    @JvmStatic
    @JvmOverloads
    fun flush(timeoutMs: Long = 5_000): Unit = client?.flush(timeoutMs) ?: Unit

    @JvmStatic
    @JvmOverloads
    fun shutdown(timeoutMs: Long = 5_000) {
        synchronized(lock) {
            val c = client
            client = null
            uninstallHandler()
            c?.shutdown(timeoutMs)
        }
    }

    @JvmStatic
    fun client(): ErrorgapClient? = client

    private fun installHandler() {
        if (captureGlobalsInstalled) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            client?.notify(
                throwable,
                NoticeOptions(
                    context = mapOf(
                        "source" to "Thread.UncaughtExceptionHandler",
                        "thread" to thread.name,
                    )
                ),
                sync = true,
            )
            previousHandler?.uncaughtException(thread, throwable)
        }
        captureGlobalsInstalled = true
    }

    private fun uninstallHandler() {
        if (!captureGlobalsInstalled) return
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        previousHandler = null
        captureGlobalsInstalled = false
    }
}
