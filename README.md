# errorgap-android

Kotlin notifier for [Errorgap](https://errorgap.com). Reports uncaught
exceptions and manual errors from Android apps, records APM transactions and
background jobs, and forwards structured logs.

This v1 is a plain Kotlin/JVM library so it can be unit-tested on any
JVM without Android tooling. It works on Android because it uses only
Android-compatible JDK APIs (`HttpURLConnection`, `Thread.UncaughtExceptionHandler`).
Device-specific metadata is supplied by the caller — see `DeviceInfo`
helpers below.

Requires Kotlin 1.9+, JDK 17 for build, Android API 24+ at runtime.

## Install (Gradle)

```kotlin
dependencies {
    implementation("com.errorgap:errorgap-android:0.7.0")
}
```

## Configure

In your `Application.onCreate()`:

```kotlin
import com.errorgap.android.Errorgap
import com.errorgap.android.ErrorgapConfiguration
import android.os.Build

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Errorgap.init(
            ErrorgapConfiguration(
                endpoint = BuildConfig.ERRORGAP_ENDPOINT,
                projectSlug = "your-project",
                apiKey = BuildConfig.ERRORGAP_API_KEY,
                environment = if (BuildConfig.DEBUG) "development" else "production",
                release = BuildConfig.VERSION_NAME,
                deviceInfo = mapOf(
                    "os_name" to "Android",
                    "os_version" to Build.VERSION.RELEASE,
                    "device_model" to Build.MODEL,
                    "manufacturer" to Build.MANUFACTURER,
                    "abi" to Build.SUPPORTED_ABIS.joinToString(","),
                    "app_version" to BuildConfig.VERSION_NAME,
                    "app_build" to BuildConfig.VERSION_CODE.toString(),
                ),
            )
        )
    }
}
```

By default `init` installs `Thread.setDefaultUncaughtExceptionHandler`;
pass `captureGlobals = false` to skip.

## Crashes

An uncaught exception usually ends the app before a report can be sent, and
the main thread may not use the network at all. Give the SDK a directory and
it writes the crash there first, sends it from a background thread, and
delivers it on the next launch if the app died before the request finished:

```kotlin
Errorgap.init(
    ErrorgapConfiguration(
        projectSlug = "my-app",
        crashDirectory = applicationContext.filesDir.path,
    )
)
```

## Remote settings

Errors, performance data and logs can each be switched off for a project in
errorgap (Project settings › What SDKs send). Installed apps follow within ten
minutes of running, with no new release. If errorgap cannot be reached,
everything stays on. `remoteSettings = false` ignores the project's switches.

## Filtering, hooks and delivery

```kotlin
ErrorgapConfiguration(
    projectSlug = "my-app",
    ignoreEnvironments = listOf("debug"),
    beforeNotify = { notice ->
        val type = (notice["errors"] as List<Map<String, Any?>>).first()["type"]
        if (type == "CancellationException") null else notice   // null drops it
    },
)

Errorgap.notify(error, NoticeOptions(userId = account.id, userEmail = account.email))
```

`filterKeys` are masked at any depth in `params` and `session`, and oversized
values are cut. A send that fails on the network or with a 5xx is retried up
to three times; after a 429 the SDK stops sending for the server's
`Retry-After`.

## Manual notification

```kotlin
try {
    risky()
} catch (e: Throwable) {
    Errorgap.notify(e, NoticeOptions(context = mapOf("component" to "checkout")))
    throw e
}
```

`notify` returns a `DeliveryResult` (`status`, `body`, `error`, `queued`).
The SDK never throws.

Backtrace frames include Kotlin/Java source excerpts when the source is
available below `rootDirectory` or as a classpath/source-JAR resource. Use
`inAppPackages` to distinguish application frames from vendor code:

```kotlin
ErrorgapConfiguration(
    projectSlug = "your-project",
    rootDirectory = projectDir.absolutePath,
    inAppPackages = listOf("com.example.myapp"),
)
```

## APM

Enable APM with `apmEnabled = true` (or `ERRORGAP_APM_ENABLED=true`) and send
web transactions with optional database or outbound HTTP spans:

```kotlin
Errorgap.notifyTransaction(
    ApmTransaction(
        method = "POST",
        path = "/orders/{id}",
        statusCode = 201,
        durationMs = 42.5,
        spans = listOf(
            ApmSpan.database(
                "select * from orders where id = 42",
                durationMs = 3.2,
                file = "com/example/Orders.kt",
                line = 41,
                function = "com.example.Orders.find",
            ),
        ),
    ),
)
```

`ApmSpan.database` normalizes SQL literals for aggregation. Background jobs can
be measured and failed jobs reported automatically:

```kotlin
Errorgap.trackJob("com.example.ReceiptJob", "critical") { spans ->
    spans.database("select 7 where id = 42", 2.1)
    runReceiptJob()
}
```

### Link API calls to server traces

Trace a call to your API and errorgap links it to the server request that
answered it (when the server's errorgap SDK records the `x-errorgap-trace`
header — Rails, Laravel, Express, Django, Spring and the rest do):

```kotlin
val spans = SpanCollector()
val started = System.nanoTime()
val response = spans.traceCall("GET /api/orders/7") { headers ->
    val request = Request.Builder().url(ordersUrl).apply {
        headers.forEach { (name, value) -> header(name, value) }
    }.build()
    okHttp.newCall(request).execute()
}
Errorgap.notifyTransaction(
    ApmTransaction(
        path = "OrderScreen",
        durationMs = (System.nanoTime() - started) / 1_000_000.0,
        spans = spans.snapshot(),
    ),
)
```

`traceCall` records an `http` span carrying the trace id it sent; the app's
trace lists each traced call with a link to its server trace, and the server
trace shows how long the app waited. For manual timing use
`val call = spans.startCall(label)`, send `call.headers`, then `call.finish()`.

### Link errors to their transaction

Every `ApmTransaction` has an `id`. Errors reported inside
`ErrorgapTransactionContext.run(id) { ... }` — and a failing `trackJob` —
carry it as `context.transaction_id`, so errorgap shows the error an
interaction raised on its trace:

```kotlin
val transaction = ApmTransaction(path = "/checkout", durationMs = 0.0)
ErrorgapTransactionContext.run(transaction.id) { submitOrder() }
```

The id is thread-local; for work that hops threads (coroutines on another
dispatcher), pass it explicitly:
`NoticeOptions(context = mapOf("transaction_id" to transaction.id))`.

## Logs

Enable log forwarding with `logsEnabled = true` and configure
`minimumLogLevel` (`trace`, `debug`, `info`, `warn`, `error`, or `fatal`):

```kotlin
Errorgap.notifyLog("payment gateway timeout", "warn", "CheckoutActivity")
```

For `java.util.logging`, attach the dependency-free bridge:

```kotlin
Logger.getLogger("").addHandler(ErrorgapLogHandler())
```

## Session replay

Session replay records a timeline of each app session, shown on error groups
and incidents in Errorgap. It is **timeline only**: no screenshots and no view
snapshots.

| Event | Recorded when |
|---|---|
| `page_load` | The app starts ("App started"), or a new session starts after 30 minutes in the background ("App opened") |
| `navigation` | A screen is tracked, or the app returns from the background |
| `api_error` | A call you pass to `Errorgap.recordCall` failed or answered 5xx |
| `error` | An error is reported, linked to its error group |
| `exit` | The app goes to the background |

It is on by default when running on Android (off on a plain JVM; pass
`replay = true` to record there), and records only what the project allows in
**Settings → Session replay**: nothing while replay is off, and a sampled share
of sessions while it is on.

Only sessions that hit an error are kept. Until an error is reported, the
session's most recent 300 events are held in memory and nothing is sent; then
everything held goes up with the error and the rest of the session follows. A
session that ends without an error is never sent.

This library does not depend on the Android SDK, so wire screens and the app
lifecycle in your `Application`:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Errorgap.init(config)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) Errorgap.appResumed()
            }
            override fun onActivityResumed(activity: Activity) {
                Errorgap.trackScreen(activity.javaClass.simpleName)
            }
            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) Errorgap.appPaused()
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
```

In Compose, call `Errorgap.trackScreen("Checkout")` from your navigation
destination (for example in a `LaunchedEffect`). Record failed calls from an
OkHttp interceptor:

```kotlin
class ErrorgapReplayInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request).also {
                Errorgap.recordCall(request.method, request.url.toString(), it.code)
            }
        } catch (e: IOException) {
            Errorgap.recordCall(request.method, request.url.toString(), 0, e)
            throw e
        }
    }
}
```

Turn it off with `Errorgap.init(config, replay = false)`, and name the app in
the session list with `replayOptions = ReplayOptions(appName = "Acme")`.

## Configuration reference

| Field | Default | Notes |
|---|---|---|
| `endpoint` | `ERRORGAP_ENDPOINT` or `http://127.0.0.1:3030` | |
| `projectSlug` | `ERRORGAP_PROJECT_SLUG` | **Required** |
| `projectId` | `ERRORGAP_PROJECT_ID` | |
| `apiKey` | `ERRORGAP_API_KEY` | Sent as `x-errorgap-project-key` |
| `environment` | `ERRORGAP_ENVIRONMENT` or `production` | |
| `release` | — | |
| `rootDirectory` | JVM working directory | Source lookup root |
| `inAppPackages` | empty | Package prefixes classified as application code |
| `async` | `true` | Background daemon thread |
| `filterKeys` | `password, token, …` | Substring, case-insensitive |
| `timeoutMs` | `5000` | HTTP request timeout |
| `queueSize` | `100` | Bounded notice queue |
| `deviceInfo` | `emptyMap()` | Caller-supplied; see Configure |
| `apmEnabled` | `ERRORGAP_APM_ENABLED` or `false` | Send APM transactions |
| `apmSampleRate` | `ERRORGAP_APM_SAMPLE_RATE` or `1` | Clamped to `0..1` |
| `logsEnabled` | `ERRORGAP_LOGS_ENABLED` or `false` | Forward structured logs |
| `minimumLogLevel` | `ERRORGAP_MINIMUM_LOG_LEVEL` or `warn` | Client-side log threshold |

## Graceful shutdown

```kotlin
Errorgap.flush(5_000)
Errorgap.shutdown(5_000) // optional: stop background worker
```

## Development

```sh
gradle test
```

## License

MIT.
