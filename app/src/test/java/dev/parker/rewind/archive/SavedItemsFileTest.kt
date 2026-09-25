package dev.parker.rewind.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SavedItemsFileTest {
    private val items = listOf(
        SavedItem("CNdump", "Boat loads of early 2000s Cartoon Network", "movies"),
        SavedItem("um.11.vhs", "Unsolved mysteries; Season 11", null),
    )

    @Test
    fun exportRoundTrips() {
        assertEquals(items, SavedItemsFile.parse(SavedItemsFile.export(items)))
    }

    @Test
    fun importsBareArrayFromPreferences() {
        val prefsJson = """[{"id":"CNDump4","title":"Cartoon Network","mediatype":"movies"},{"id":"evenstevensclips"}]"""
        assertEquals(
            listOf(SavedItem("CNDump4", "Cartoon Network", "movies"), SavedItem("evenstevensclips", "evenstevensclips", null)),
            SavedItemsFile.parse(prefsJson),
        )
    }

    @Test
    fun importsUrlListAndDropsJunkAndDuplicates() {
        val text = """
            https://archive.org/details/afv-vol-one
            not a url at all
            archive.org/download/BlankVHSTape6hr02min/file.mp4
            afv-vol-one
        """.trimIndent()
        assertEquals(listOf("afv-vol-one", "BlankVHSTape6hr02min"), SavedItemsFile.parse(text).map { it.identifier })
    }

    @Test
    fun rejectsFilesWithNothingUsable() {
        assertThrows(IllegalArgumentException::class.java) { SavedItemsFile.parse("{\"items\": []}") }
        assertThrows(IllegalArgumentException::class.java) { SavedItemsFile.parse("{broken") }
    }
}
