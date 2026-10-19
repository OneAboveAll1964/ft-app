package app.ft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringsTest {
    private class Table(val strings: Map<String, String>, val arrays: Map<String, List<String>>)

    private fun read(folder: String): Table {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$folder/strings.xml"))
        val strings = linkedMapOf<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            if (e.getAttribute("translatable") != "false") strings[e.getAttribute("name")] = e.textContent
        }
        val arrays = linkedMapOf<String, List<String>>()
        val lists = doc.getElementsByTagName("string-array")
        for (i in 0 until lists.length) {
            val e = lists.item(i) as Element
            val items = e.getElementsByTagName("item")
            arrays[e.getAttribute("name")] = (0 until items.length).map { items.item(it).textContent }
        }
        return Table(strings, arrays)
    }

    private val english = read("values")
    private val translations = mapOf(
        "Arabic" to read("values-ar"),
        "Sorani" to read("values-b+ckb"),
        "Badini" to read("values-b+kmr+Arab"),
        "Russian" to read("values-ru")
    )

    private fun placeholders(s: String) = Regex("%(\\d+)\\$([sd])").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun everyLanguageHasEveryString() {
        translations.forEach { (name, t) ->
            assertEquals("$name strings", english.strings.keys, t.strings.keys)
            assertEquals("$name lists", english.arrays.keys, t.arrays.keys)
            english.arrays.forEach { (key, items) -> assertEquals("$name $key", items.size, t.arrays.getValue(key).size) }
        }
    }

    @Test
    fun everyTranslationKeepsTheSameBlanks() {
        translations.forEach { (name, t) ->
            english.strings.forEach { (key, value) ->
                assertEquals("$name $key", placeholders(value), placeholders(t.strings.getValue(key)))
            }
        }
    }

    @Test
    fun kurdishUsesKurdishLettersAndArabicUsesArabicOnes() {
        val arabicOnly = "يكةىًٌٍَُِّْ".toSet()
        val kurdishOnly = "یکەڵڕێۆڤگچپژ".toSet()
        fun all(t: Table) = t.strings.values + t.arrays.values.flatten()
        all(translations.getValue("Arabic")).forEach { s -> assertTrue("Kurdish letter in Arabic: $s", s.none { it in kurdishOnly }) }
        listOf("Sorani", "Badini").forEach { name ->
            all(translations.getValue(name)).forEach { s -> assertTrue("Arabic-only letter in $name: $s", s.none { it in arabicOnly }) }
        }
    }

    @Test
    fun russianIsWrittenInCyrillic() {
        val t = translations.getValue("Russian")
        (t.strings.values + t.arrays.values.flatten()).forEach { s -> assertTrue("Arabic script in Russian: $s", s.none { it in '\u0600'..'\u06FF' }) }
        val cyrillic = t.strings.values.count { s -> s.any { it in '\u0400'..'\u04FF' } }
        assertTrue("too few Russian strings are in Cyrillic: $cyrillic", cyrillic > t.strings.size * 3 / 4)
    }
}
