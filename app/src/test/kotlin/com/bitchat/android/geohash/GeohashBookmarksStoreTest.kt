package com.bitchat.android.geohash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeohashBookmarksStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = GeohashBookmarksStore.getInstance(context)
    private val prefs = context.getSharedPreferences("geohash_prefs", Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        store.clearAll()
    }

    @Test
    fun `name resolved before a panic wipe is not persisted after it`() {
        store.clearAll()
        store.add("u4pruyd")
        val generation = store.currentWipeGeneration()

        store.clearAll()

        assertFalse(store.applyResolvedName("u4pruyd", "Aalborg", generation))
        assertTrue(store.bookmarkNames.value.isEmpty())
        assertNull(prefs.getString("locationChannel.bookmarkNames", null))
        assertNull(prefs.getString("locationChannel.bookmarks", null))
    }

    @Test
    fun `name is not persisted for a geohash that is no longer bookmarked`() {
        store.clearAll()
        store.add("u4pruyd")
        val generation = store.currentWipeGeneration()
        store.remove("u4pruyd")

        assertFalse(store.applyResolvedName("u4pruyd", "Aalborg", generation))
        assertTrue(store.bookmarkNames.value.isEmpty())
    }

    @Test
    fun `name resolved in the current generation is persisted`() {
        store.clearAll()
        store.add("u4pruyd")

        assertTrue(store.applyResolvedName("u4pruyd", "Aalborg", store.currentWipeGeneration()))
        assertTrue(prefs.getString("locationChannel.bookmarkNames", null)!!.contains("Aalborg"))
    }
}
