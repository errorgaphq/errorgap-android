package io.errorgap.android

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfigurationTests {
    @Test fun defaultsWhenNothingProvided() {
        val cfg = ErrorgapConfiguration()
        assertTrue(cfg.endpoint.isNotEmpty())
        assertTrue(cfg.async)
        assertTrue(cfg.filterKeys.contains("password"))
    }

    @Test fun validateThrowsWhenProjectSlugMissing() {
        val cfg = ErrorgapConfiguration(projectSlug = null)
        val ex = assertThrows(IllegalArgumentException::class.java) { cfg.validate() }
        assertTrue(ex.message?.contains("projectSlug") == true)
    }

    @Test fun validatePassesWhenProjectSlugPresent() {
        val cfg = ErrorgapConfiguration(projectSlug = "demo")
        cfg.validate()
    }
}
