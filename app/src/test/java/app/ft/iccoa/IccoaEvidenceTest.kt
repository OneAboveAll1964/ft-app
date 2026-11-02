package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaEvidenceTest {
    @Test fun changedAdvertisementsFromSameDeviceArePreserved() {
        val samples = AdvertisementSamples()
        assertTrue(samples.record("car", "0102"))
        assertFalse(samples.record("car", "0102"))
        assertTrue(samples.record("car", "0103"))
    }

    @Test fun evidenceIsBoundedWithoutExcludingExistingDevices() {
        val samples = AdvertisementSamples(perDevice = 2, maxDevices = 2)
        assertTrue(samples.record("one", "a"))
        assertTrue(samples.record("two", "a"))
        assertFalse(samples.record("three", "a"))
        assertTrue(samples.record("one", "b"))
        assertFalse(samples.record("one", "c"))
        assertTrue(samples.record("two", "b"))
    }

    @Test fun unrelatedBluetoothServicesAreNotIccoaEvidence() {
        assertFalse(IccoaEvidence.hint(null, listOf("0000fe2c-0000-1000-8000-00805f9b34fb")))
        assertTrue(IccoaEvidence.hint("Toyota", listOf(IccoaEvidence.ADVERTISED_SERVICE)))
        assertFalse(IccoaEvidence.hint("Baidu CarLife", emptyList()))
        assertTrue(IccoaEvidence.hint(null, listOf(IccoaEvidence.REPORTED_SERVICE.uppercase())))
        assertTrue(IccoaEvidence.hint("ICCOA Carlink", emptyList()))
    }
}
