package com.errorgap.android

object Errorgap {
    @Volatile private var client: ErrorgapClient? = null
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val lock = Any()
    @Volatile private var captureGlobalsInstalled = false
    @Volatile private var replay: ReplayRecorder? = null

    /**
     * @param replay Session replay (a timeline of the app session, no
     *   screenshots). Defaults to on when running on Android; it records only
     *   what the project's replay settings allow (Settings → Session replay).
     *   `false` turns it off, `true` records on a plain JVM too.
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        configuration: ErrorgapConfiguration,
        captureGlobals: Boolean = true,
        replay: Boolean? = null,
        replayOptions: ReplayOptions = ReplayOptions(),
    ) {
        synchronized(lock) {
            configuration.validate()
            val newClient = ErrorgapClient(configuration)
            client = newClient
            this.replay?.stop()
            this.replay = null
            if (replay ?: runningOnAndroid()) {
                val recorder = ReplayRecorder(configuration, replayOptions)
                this.replay = recorder
                newClient.errorObserver = { label -> recorder.errorStarted(label) }
                recorder.start()
            }
            if (captureGlobals) installHandler() else uninstallHandler()
            // Crashes the last launch could not deliver.
            if (configuration.crashDirectory != null) {
                Thread({ runCatching { newClient.sendStoredCrashes() } }, "errorgap-stored-crashes").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    /** Record the screen now showing in the session replay timeline. */
    @JvmStatic
    fun trackScreen(name: String) {
        replay?.screen(name)
    }

    /**
     * The app went to the background: ends the replay batch, and after 30
     * minutes away the next session starts. Call it from your lifecycle
     * callbacks (see the README).
     */
    @JvmStatic
    fun appPaused() {
        replay?.paused()
    }

    /** The app is in the foreground again. */
    @JvmStatic
    fun appResumed() {
        replay?.resumed()
    }

    /**
     * Record an API call in the session replay timeline when it failed
     * (`status` 0 or an exception) or answered 5xx. Call it from an OkHttp
     * interceptor or your own HTTP layer.
     */
    @JvmStatic
    @JvmOverloads
    fun recordCall(method: String, url: String, status: Int, error: Throwable? = null) {
        if (error == null && status in 1..499) return
        replay?.apiError(method, url, if (error != null) 0 else status)
    }

    private fun runningOnAndroid(): Boolean = try {
        Class.forName("android.os.Build")
        true
    } catch (_: Throwable) {
        false
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
            replay?.stop()
            replay = null
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
            // Stored first, then sent off this thread: see notifyCrash.
            runCatching {
                client?.notifyCrash(
                    throwable,
                    NoticeOptions(
                        context = mapOf(
                            "source" to "Thread.UncaughtExceptionHandler",
                            "thread" to thread.name,
                        )
                    ),
                )
            }
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
