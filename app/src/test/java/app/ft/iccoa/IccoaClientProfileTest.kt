package app.ft.iccoa

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IccoaClientProfileTest {
    @Test fun clientInfoUsesCompatibilityVendorAndOwnIdentity() {
        val profile = IccoaClientProfile("abcd12", "02:34:56:78:9a:bc")
        val info = JSONObject(profile.clientInfo("123456", "CPH2581", 36).toString(Charsets.UTF_8))
        assertEquals("Xiaomi", info.getString("manufactures"))
        assertEquals("CPH2581", info.getString("model"))
        assertEquals("abcd12", info.getString("id"))
        assertEquals("02:34:56:78:9a:bc", info.getString("mac"))
        assertEquals("F-Tap", info.getString("name"))
        assertEquals("123456", info.getString("pinCodeOrAuthentication"))
        assertEquals(1002, info.getInt("type"))
        assertEquals(36, info.getInt("channel"))
    }

    @Test fun generatedIdentityIsLocalUnicastAndValid() {
        repeat(16) {
            val profile = IccoaClientProfile.generate()
            assertTrue(IccoaClientProfile.valid(profile.id, profile.mac))
        }
        assertFalse(IccoaClientProfile.valid("a1b2c3", "01:00:00:00:00:00"))
        assertFalse(IccoaClientProfile.valid("bad-id", "02:00:00:00:00:00"))
    }

    @Test fun wifiFrequencyMatchesReferenceChannelMapping() {
        assertEquals(1, IccoaClientProfile.wifiChannel(2412))
        assertEquals(13, IccoaClientProfile.wifiChannel(2472))
        assertEquals(14, IccoaClientProfile.wifiChannel(2484))
        assertEquals(36, IccoaClientProfile.wifiChannel(5180))
        assertEquals(165, IccoaClientProfile.wifiChannel(5825))
        assertEquals(1, IccoaClientProfile.wifiChannel(-1))
    }
}
