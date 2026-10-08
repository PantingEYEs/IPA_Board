package com.example.ipa_board

import android.content.SharedPreferences
import com.example.ipa_board.clipboard.ClipboardRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ClipboardRepositoryTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var repository: ClipboardRepository

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        repository = ClipboardRepository(prefsOverride = fakePrefs)
    }

    @Test
    fun testAddMultipleClipsRecordedWithTimestamps() {
        val clip1 = repository.addClip("Text 1")
        Thread.sleep(10)
        val clip2 = repository.addClip("Text 2")

        assertNotNull(clip1)
        assertNotNull(clip2)
        assertEquals(2, repository.getItems().size)
        assertTrue(clip2!!.timestamp >= clip1!!.timestamp)
    }

    @Test
    fun testAutoDeleteEarliestUnpinnedWhenExceeding20() {
        // Add 25 unpinned items
        for (index in 1..25) {
            Thread.sleep(2)
            repository.addClip("Clip $index")
        }

        assertEquals(20, repository.getUnpinnedCount())
        val items = repository.getItems()
        assertEquals(20, items.size)

        // Clip 1 to Clip 5 (earliest) should be automatically deleted
        assertFalse(items.any { it.text == "Clip 1" })
        assertFalse(items.any { it.text == "Clip 5" })
        assertTrue(items.any { it.text == "Clip 6" })
        assertTrue(items.any { it.text == "Clip 25" })
    }

    @Test
    fun testPinningItemExemptsFrom20UnpinnedLimit() {
        // Add 1 item and pin it
        val itemToPin = repository.addClip("Pinned Item")!!
        repository.togglePin(itemToPin.id)

        // Add 20 unpinned items
        for (i in 1..20) {
            repository.addClip("Unpinned $i")
        }

        assertEquals(1, repository.getPinnedCount())
        assertEquals(20, repository.getUnpinnedCount())
        assertEquals(21, repository.getItems().size)

        // Pinned item must still be present
        assertTrue(repository.getItems().any { it.id == itemToPin.id && it.isPinned })
    }

    @Test
    fun testUnpinningResetsTimestampToPreventInstantDeletion() {
        // Pin an item first
        val pinnedItem = repository.addClip("Old Pinned Item")!!
        repository.togglePin(pinnedItem.id)

        // Simulate 20 unpinned items added later (with newer timestamps)
        for (i in 1..20) {
            Thread.sleep(2)
            repository.addClip("Unpinned Item $i")
        }

        assertEquals(20, repository.getUnpinnedCount())
        assertEquals(1, repository.getPinnedCount())

        // Now unpin the pinned item with reset timestamp to current time T_now
        val now = System.currentTimeMillis() + 1000
        val unpinned = repository.togglePin(pinnedItem.id, currentTimeMs = now)

        assertNotNull(unpinned)
        assertFalse(unpinned!!.isPinned)
        assertEquals(now, unpinned.timestamp)

        // Unpinned count must stay at max 20
        assertEquals(20, repository.getUnpinnedCount())

        // The newly unpinned item MUST remain in repository because its timestamp was reset to 'now'
        val items = repository.getItems()
        assertTrue(items.any { it.id == pinnedItem.id })

        // The oldest unpinned item ("Unpinned Item 1") should have been pruned instead
        assertFalse(items.any { it.text == "Unpinned Item 1" })
    }

    @Test
    fun testManualDeletionAndRestore() {
        val clip = repository.addClip("To Delete")!!
        assertEquals(1, repository.getItems().size)

        // Delete manually
        val deleted = repository.deleteItem(clip.id)
        assertTrue(deleted)
        assertEquals(0, repository.getItems().size)

        // Restore item (Undo)
        repository.restoreItem(clip)
        assertEquals(1, repository.getItems().size)
        assertEquals("To Delete", repository.getItems()[0].text)
    }

    @Test
    fun deletedSystemClipIsNotReaddedByRefreshOrServiceRestart() {
        val item = repository.syncSystemClip("Current system clip", 100)!!
        repository.deleteItem(item.id)
        assertNull(repository.syncSystemClip(item.text, 100))
        val restarted = ClipboardRepository(prefsOverride = fakePrefs)
        assertNull(restarted.syncSystemClip(item.text, 100))
        assertTrue(restarted.getItems().isEmpty())
        assertNotNull(restarted.syncSystemClip(item.text, 200))
        assertEquals(1, restarted.getItems().size)
    }

    @Test
    fun repeatedSyncPreservesHistoryAndUndo() {
        val item = repository.syncSystemClip("Undo clip", 100)!!
        val newer = repository.addClip("Newer history")!!
        assertNull(repository.syncSystemClip(item.text, 100))
        assertEquals(item, repository.getItemById(item.id))
        assertNotNull(repository.getItemById(newer.id))
        repository.deleteItem(item.id)
        repository.restoreItem(item)
        assertNull(repository.syncSystemClip(item.text, 100))
        assertEquals(item, repository.getItemById(item.id))
    }

    @Test
    fun clearedHistoryStaysEmptyUntilClipboardChanges() {
        repository.syncSystemClip("Old clip", 100)
        repository.clearAllUnpinned()
        assertNull(repository.syncSystemClip("Old clip", 100))
        assertTrue(repository.getItems().isEmpty())
        assertNotNull(repository.syncSystemClip("Different clip", 100))
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = map

        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            (map[key] as? Set<String>) ?: defValues

        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val tempMap = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor { tempMap[key] = value; return this }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor { tempMap[key] = values; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { tempMap[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { tempMap[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { tempMap[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { tempMap[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { tempMap.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) { map.clear(); clearAll = false }
                map.putAll(tempMap)
                tempMap.clear()
            }
        }
    }
}
