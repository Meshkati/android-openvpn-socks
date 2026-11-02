package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileListTest {

    private val list = ProfileList(listOf(1L, 2L, 3L), active = 2)

    @Test
    fun parsesAndFormats() {
        val parsed = ProfileList.parse("1, 3,2", 3)
        assertEquals(listOf(1L, 3L, 2L), parsed.ids)
        assertEquals(3L, parsed.active)
        assertEquals("1,3,2", parsed.format())
    }

    @Test
    fun parseSkipsJunkAndRepeats() {
        assertEquals(listOf(1L, 2L), ProfileList.parse("1,,x,2,1,0,-4", 1).ids)
    }

    @Test
    fun parseFallsBackToTheFirstProfile() {
        assertEquals(1L, ProfileList.parse("1,2", 9).active)
        assertEquals(ProfileList.NONE, ProfileList.parse("", 1).active)
    }

    @Test
    fun addsAtTheEndOrAfterTheOriginal() {
        assertEquals(listOf(1L, 2L, 3L, 4L), list.add(4).ids)
        assertEquals(listOf(1L, 4L, 2L, 3L), list.add(4, after = 1).ids)
        assertEquals(listOf(1L, 2L, 3L, 4L), list.add(4, after = 3).ids)
        assertEquals(list, list.add(2))
        assertEquals(2L, list.add(4).active) // a new profile isn't in use yet
    }

    @Test
    fun removingTheProfileInUseUsesTheFirstOneLeft() {
        val removed = list.remove(2)
        assertEquals(listOf(1L, 3L), removed.ids)
        assertEquals(1L, removed.active)
        assertEquals(3L, ProfileList(listOf(1L, 3L), 1).remove(1).active)
    }

    @Test
    fun removingAnotherProfileKeepsTheOneInUse() {
        assertEquals(2L, list.remove(3).active)
    }

    @Test
    fun removingTheLastProfileLeavesNone() {
        val empty = ProfileList(listOf(5L), 5).remove(5)
        assertEquals(emptyList<Long>(), empty.ids)
        assertEquals(ProfileList.NONE, empty.active)
    }

    @Test
    fun restoreUndoesRemove() {
        assertEquals(list, list.remove(2).restore(2, 1, wasActive = true))
        assertEquals(list, list.remove(3).restore(3, 2, wasActive = false))
        assertEquals(list, list.remove(1).restore(1, 0, wasActive = false))
    }

    @Test
    fun restoreKeepsTheIndexInRange() {
        assertEquals(listOf(1L, 2L, 3L, 7L), list.restore(7, 99, wasActive = false).ids)
        assertEquals(list, list.restore(3, 0, wasActive = true)) // already there
    }

    @Test
    fun movesAndStopsAtTheEnds() {
        assertEquals(listOf(2L, 1L, 3L), list.move(2, -1).ids)
        assertEquals(listOf(1L, 3L, 2L), list.move(2, 1).ids)
        assertEquals(list, list.move(1, -1))
        assertEquals(list, list.move(3, 1))
        assertEquals(list, list.move(9, 1))
        assertEquals(2L, list.move(2, 1).active)
    }

    @Test
    fun usesOnlyAProfileInTheList() {
        assertEquals(3L, list.use(3).active)
        assertEquals(2L, list.use(9).active)
    }
}
