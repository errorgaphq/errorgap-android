# errorgap-android

Kotlin notifier for [Errorgap](https://errorgap.com). Reports uncaught
exceptions and manual errors from Android apps.

This v1 is a plain Kotlin/JVM library so it can be unit-tested on any
JVM without Android tooling. It works on Android because it uses only
Android-compatible JDK APIs (`HttpURLConnection`, `Thread.UncaughtExceptionHandler`).
Device-specific metadata is supplied by the caller — see `DeviceInfo`
helpers below.

Requires Kotlin 1.9+, JDK 17 for build, Android API 24+ at runtime.

## Install (Gradle)

```kotlin
dependencies {
    implementation("io.errorgap:errorgap-android:0.1.0")
}
```

## Configure

In your `Application.onCreate()`:

```kotlin
import io.errorgap.android.Errorgap
import io.errorgap.android.ErrorgapConfiguration
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

## Configuration reference

| Field | Default | Notes |
|---|---|---|
| `endpoint` | `ERRORGAP_ENDPOINT` or `http://127.0.0.1:3030` | |
| `projectSlug` | `ERRORGAP_PROJECT_SLUG` | **Required** |
| `projectId` | `ERRORGAP_PROJECT_ID` | |
| `apiKey` | `ERRORGAP_API_KEY` | Sent as `x-errorgap-project-key` |
| `environment` | `ERRORGAP_ENVIRONMENT` or `production` | |
| `release` | — | |
| `async` | `true` | Background daemon thread |
| `filterKeys` | `password, token, …` | Substring, case-insensitive |
| `timeoutMs` | `5000` | HTTP request timeout |
| `queueSize` | `100` | Bounded notice queue |
| `deviceInfo` | `emptyMap()` | Caller-supplied; see Configure |

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
