package io.errorgap.android

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FilterTests {
    private val defaults = listOf("password", "token", "secret", "api_key", "authorization", "cookie")

    @Test fun masksFilteredKeys() {
        val out = Filter.params(
            mapOf("username" to "alice", "password" to "hunter2", "access_token" to "x"),
            defaults,
        )
        assertEquals("alice", out["username"])
        assertEquals("[FILTERED]", out["password"])
        assertEquals("[FILTERED]", out["access_token"])
    }

    @Test fun recursesIntoNestedMap() {
        val out = Filter.params(
            mapOf("user" to mapOf<String, Any?>("name" to "alice", "api_key" to "x")),
            defaults,
        )
        @Suppress("UNCHECKED_CAST")
        val user = out["user"] as Map<String, Any?>
        assertEquals("alice", user["name"])
        assertEquals("[FILTERED]", user["api_key"])
    }

    @Test fun caseInsensitive() {
        val out = Filter.params(mapOf("Authorization" to "Bearer xyz"), defaults)
        assertEquals("[FILTERED]", out["Authorization"])
    }
}
