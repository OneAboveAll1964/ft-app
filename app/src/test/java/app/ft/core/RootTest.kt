package app.ft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTest {
    private val samsung = """
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <SoftAp>
          <string name="WifiSsid">&quot;OneAboveAll&quot;</string>
          <string name="Passphrase">s3cr3t-p@ss</string>
          <int name="SecurityType" value="1" />
          <int name="ApBand" value="0" />
          <boolean name="HiddenSSID" value="false" />
        </SoftAp>
    """.trimIndent()

    @Test
    fun readsSamsungSoftApConfig() {
        val ap = Root.parseSoftAp(samsung)!!
        assertEquals("OneAboveAll", ap.ssid)
        assertEquals("s3cr3t-p@ss", ap.passphrase)
        assertFalse(ap.open)
        assertEquals(2, ap.band)
    }

    @Test
    fun fiveGhzAndOpenNetworksAreUnderstood() {
        val xml = """
            <SoftAp>
              <string name="SSID">&quot;CarAP&quot;</string>
              <int name="SecurityType" value="0" />
              <int name="ApBand" value="1" />
            </SoftAp>
        """.trimIndent()
        val ap = Root.parseSoftAp(xml)!!
        assertEquals("CarAP", ap.ssid)
        assertTrue(ap.open)
        assertEquals(5, ap.band)
    }

    @Test
    fun aConfigWithNoSsidIsRejected() {
        assertNull(Root.parseSoftAp("<SoftAp><int name=\"ApBand\" value=\"0\" /></SoftAp>"))
    }

    @Test
    fun securityIsInferredFromThePassphraseWhenTypeIsMissing() {
        val ap = Root.parseSoftAp("<SoftAp><string name=\"SSID\">&quot;X&quot;</string><string name=\"Passphrase\">abc</string></SoftAp>")!!
        assertFalse(ap.open)
    }
}
