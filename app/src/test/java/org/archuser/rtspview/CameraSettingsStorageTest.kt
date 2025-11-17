package org.archuser.rtspview

import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CameraSettingsStorageTest {

    @Test
    fun parseCameraSettings_handlesLegacyPythonFormat() {
        val legacyJson = """
            [
                {
                    "title": "Porch",
                    "user": "admin",
                    "pass": "secret",
                    "ip": "192.168.0.50",
                    "port": 8554,
                    "slug": "/cam/realmonitor",
                    "channel": "2",
                    "subtype": "1",
                    "transport": "udp",
                    "latency": 250
                }
            ]
        """.trimIndent()

        val parsed = parseCameraSettings(legacyJson)

        assertNotNull(parsed)
        assertEquals(1, parsed.size)
        val config = parsed.first()
        assertEquals("Porch", config.title)
        assertEquals("admin", config.username)
        assertEquals("secret", config.password)
        assertEquals("192.168.0.50", config.host)
        assertEquals("8554", config.port)
        assertEquals("/cam/realmonitor", config.slug)
        assertEquals("2", config.channel)
        assertEquals("1", config.subtype)
        assertEquals(RtspTransport.UDP, config.transport)
        assertEquals(250, config.latencyMs)
    }

    @Test
    fun serializeCameraSettings_includesLegacyAliases() {
        val config = CameraConfig(
            title = "Garage",
            username = "viewer",
            password = "hunter2",
            host = "10.0.0.10",
            port = "8555",
            slug = "/stream",
            channel = "1",
            subtype = "0",
            transport = RtspTransport.TCP,
            latencyMs = 125
        )

        val output = serializeCameraSettings(listOf(config))
        val array = JSONArray(output)
        assertEquals(1, array.length())
        val obj = array.getJSONObject(0)

        assertEquals("viewer", obj.getString("username"))
        assertEquals("viewer", obj.getString("user"))
        assertEquals("hunter2", obj.getString("password"))
        assertEquals("hunter2", obj.getString("pass"))
        assertEquals("10.0.0.10", obj.getString("host"))
        assertEquals("10.0.0.10", obj.getString("ip"))
        assertEquals("8555", obj.getString("portString"))
        assertEquals(8555, obj.getInt("port"))
        assertEquals(125, obj.getInt("latencyMs"))
        assertEquals(125, obj.getInt("latency"))
        assertTrue(obj.getString("transport").equals("tcp", ignoreCase = true))
    }
}
