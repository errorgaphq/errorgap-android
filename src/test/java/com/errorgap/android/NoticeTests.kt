package com.errorgap.android

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NoticeTests {
    private fun cfg() = ErrorgapConfiguration(
        projectSlug = "demo",
        projectId = "p_1",
        environment = "test",
        release = "1.2.3",
        deviceInfo = mapOf("os_name" to "Android", "device_model" to "Pixel 8"),
    )

    @Test fun capturesTypeAndMessage() {
        val notice = Notice.build(IllegalArgumentException("boom"), cfg())
        @Suppress("UNCHECKED_CAST")
        val errors = notice["errors"] as List<Map<String, Any?>>
        assertEquals("IllegalArgumentException", errors[0]["type"])
        assertEquals("boom", errors[0]["message"])
    }

    @Test fun includesNotifierIdentification() {
        val notice = Notice.build(RuntimeException("x"), cfg())
        @Suppress("UNCHECKED_CAST")
        val ctx = notice["context"] as Map<String, Any?>
        assertEquals("errorgap-android", ctx["notifier"])
        assertEquals(Version.CURRENT, ctx["notifier_version"])
        assertEquals("test", ctx["environment"])
        assertEquals("1.2.3", ctx["release"])
    }

    @Test fun includesDeviceInfoInEnvironment() {
        val notice = Notice.build(RuntimeException("x"), cfg())
        @Suppress("UNCHECKED_CAST")
        val env = notice["environment"] as Map<String, Any?>
        assertEquals("Android", env["os_name"])
        assertEquals("Pixel 8", env["device_model"])
    }

    @Test fun filtersSensitiveParams() {
        val notice = Notice.build(
            RuntimeException("x"),
            cfg(),
            NoticeOptions(params = mapOf("username" to "alice", "password" to "hunter2")),
        )
        @Suppress("UNCHECKED_CAST")
        val params = notice["params"] as Map<String, Any?>
        assertEquals("alice", params["username"])
        assertEquals("[FILTERED]", params["password"])
    }

    @Test fun includesProjectId() {
        val notice = Notice.build(RuntimeException("x"), cfg())
        assertEquals("p_1", notice["project_id"])
    }

    @Test fun includesInlineKotlinSource() {
        val notice = Notice.build(
            RuntimeException("source excerpt"),
            cfg().copy(
                rootDirectory = System.getProperty("user.dir"),
                inAppPackages = listOf("com.errorgap.android"),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val errors = notice["errors"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val frames = errors[0]["backtrace"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val source = frames.firstNotNullOfOrNull { it["source"] as? Map<String, Any?> }
        assertNotNull(source)
        assertTrue((source!!["lines"] as List<*>).any { it.toString().contains("source excerpt") })
    }

    @Test fun honorsExplicitInAppPackagePrefixes() {
        val frames = Backtrace.fromThrowable(
            RuntimeException("x"),
            System.getProperty("user.dir"),
            listOf("com.example.application"),
        )
        assertTrue(frames.none { it["in_app"] == true })
    }
}
