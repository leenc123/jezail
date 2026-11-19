package com.azzahid.jezail

import com.azzahid.jezail.core.api.DiscoveryResponder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryResponderTest {

    private fun parse(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `buildReply produces valid jezail-hello payload`() {
        val raw = DiscoveryResponder.buildReply(
            serverPort = 8080,
            deviceName = "TestBox",
            androidId = "abc123",
            appVersion = "2.0.0",
            isRooted = true,
            authEnabled = false,
        )
        val json = parse(raw)

        assertEquals("jezail-hello", json.getValue("type").jsonPrimitive.content)
        assertEquals(1, json.getValue("v").jsonPrimitive.int)
        assertEquals("abc123", json.getValue("id").jsonPrimitive.content)
        assertEquals("TestBox", json.getValue("name").jsonPrimitive.content)
        assertEquals(8080, json.getValue("http").jsonPrimitive.int)
        assertEquals(true, json.getValue("root").jsonPrimitive.boolean)
        assertEquals(false, json.getValue("auth").jsonPrimitive.boolean)
        assertEquals("2.0.0", json.getValue("app").jsonPrimitive.content)
        assertEquals(1, json.getValue("api").jsonPrimitive.int)
    }

    @Test
    fun `buildReply escapes quotes and control chars so payload stays valid`() {
        val raw = DiscoveryResponder.buildReply(
            serverPort = 8080,
            deviceName = "Box \"A\"\nRoom 1",
            androidId = null,
            appVersion = null,
            isRooted = false,
            authEnabled = false,
        )
        val json = parse(raw)

        assertEquals("Box \"A\"\nRoom 1", json.getValue("name").jsonPrimitive.content)
        assertTrue(json["id"].toString() == "null")
    }

    @Test
    fun `magic string matches desktop client contract`() {
        assertEquals("JEZAIL_DISCOVER_V1", DiscoveryResponder.MAGIC)
        assertEquals(8090, DiscoveryResponder.DISCOVERY_PORT)
    }
}
