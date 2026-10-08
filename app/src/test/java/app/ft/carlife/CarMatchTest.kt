package app.ft.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarMatchTest {
    private fun peer(name: String, address: String, owner: Boolean = false) = CarFinder.Peer(name, address, 3, owner)

    private val tv = peer("[LG] webOS TV UA85506LA", "e2:85:4d:07:7c:e6")
    private val printer = peer("DIRECT-cb-HP M181 LaserJet", "da:12:65:11:22:33", owner = true)

    @Test
    fun theCorollaIsFoundByItsName() {
        val corolla = peer("Android_f4ec", "8a:b6:bd:56:ca:9c", owner = true)
        val hit = CarMatch.pick(listOf(tv, corolla), "Android_f4ec", "")
        assertEquals(corolla to CarMatch.Why.NAME, hit)
    }

    @Test
    fun theFrontlandersNamelessGroupIsFoundByItsBluetoothAddress() {
        val frontlander = peer(" ", "ee:a7:ad:83:5f:b3", owner = true)
        val hit = CarMatch.pick(listOf(tv, printer, frontlander), "_ClfWfd_vehicle-ePI34", "EC:A7:AD:83:5F:B4")
        assertEquals(frontlander to CarMatch.Why.ADDRESS, hit)
    }

    @Test
    fun bothCarsWifiAddressesComeFromTheirBluetoothAddresses() {
        assertTrue(CarMatch.sameRadio("ee:a7:ad:83:5f:b3", "EC:A7:AD:83:5F:B4"))
        assertTrue(CarMatch.sameRadio("8a:b6:bd:56:ca:9c", "88:B6:BD:56:CA:9B"))
    }

    @Test
    fun anotherDeviceFromTheSameMakerIsNotTheCar() {
        assertFalse(CarMatch.sameRadio("ee:a7:ad:83:60:10", "EC:A7:AD:83:5F:B4"))
        assertFalse(CarMatch.sameRadio("ee:a7:ae:83:5f:b3", "EC:A7:AD:83:5F:B4"))
        assertFalse(CarMatch.sameRadio("not a mac", "EC:A7:AD:83:5F:B4"))
    }

    @Test
    fun aLoneNamelessGroupIsTakenOnlyWhenTheCarGaveAName() {
        val nameless = peer("", "12:34:56:78:9a:bc", owner = true)
        assertEquals(nameless to CarMatch.Why.NAMELESS_GROUP, CarMatch.pick(listOf(tv, nameless), "_ClfWfd_vehicle-ePI34", ""))
        assertNull(CarMatch.pick(listOf(tv, nameless), "", ""))
    }

    @Test
    fun twoNamelessGroupsAreTooUncertain() {
        val a = peer("", "12:34:56:78:9a:bc", owner = true)
        val b = peer(" ", "22:34:56:78:9a:bc", owner = true)
        assertNull(CarMatch.pick(listOf(a, b), "_ClfWfd_vehicle-ePI34", ""))
    }

    @Test
    fun aBlankNameNeverMatchesTheWantedNameByContainment() {
        val blank = peer(" ", "12:34:56:78:9a:bc", owner = false)
        assertNull(CarMatch.pick(listOf(blank), "_ClfWfd_vehicle-ePI34", ""))
    }

    @Test
    fun withNoNameKnownACarLifeNamedDeviceIsStillTaken() {
        val carlife = peer("CarLife-1234", "02:00:00:00:00:01", owner = true)
        assertEquals(carlife to CarMatch.Why.NAME, CarMatch.pick(listOf(tv, carlife), "", ""))
    }
}
