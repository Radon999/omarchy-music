package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaylistFoldersTest {
    private val file = File.createTempFile("folders", ".json").apply { delete(); deleteOnExit() }

    @Test fun holdsYoursAndYourServices() {
        val f = PlaylistFolders(file)
        val gym = f.create("Gym")
        val mine = FolderItem.mine(Playlist("abc", "Mine", 0.0, 0.0))
        val tidal = FolderItem.service(Album("t1", "Tidal Mix", thumb = "x.jpg"), "tidal")
        assertTrue(f.add(gym.id, mine))
        assertTrue(f.add(gym.id, tidal))
        assertFalse(f.add(gym.id, tidal.copy()))   // once
        assertEquals(listOf("Mine", "Tidal Mix"), PlaylistFolders(file).get(gym.id)!!.playlists.map { it.title })   // kept
    }

    @Test fun renameRemoveDeleteForget() {
        val f = PlaylistFolders(file)
        val a = f.create("b folder"); val b = f.create("A folder")
        assertEquals(listOf("A folder", "b folder"), f.all().map { it.name })
        val mine = FolderItem.mine(Playlist("abc", "Mine", 0.0, 0.0))
        f.add(a.id, mine); f.add(b.id, mine)
        f.remove(a.id, mine.key)
        assertEquals(0, f.get(a.id)!!.playlists.size)
        f.forget("mine::abc")
        assertEquals(0, f.get(b.id)!!.playlists.size)
        f.rename(b.id, "Z"); f.delete(a.id)
        assertEquals(listOf("Z"), f.all().map { it.name })
    }
}
