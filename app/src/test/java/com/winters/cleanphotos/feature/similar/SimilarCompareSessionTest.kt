package com.winters.cleanphotos.feature.similar

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.domain.model.SimilarMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimilarCompareSessionTest {

    @Test
    fun hiddenPhotoDropsOutAndThePageFollowsTheSmallerGroup() {
        val group = SimilarGroup(listOf(shot(1), shot(2), shot(3)), recommendedId = 1)
        val hidden = compareSession(group.key, listOf(group), setOf(3L))
        assertEquals(listOf(1L, 2L), hidden!!.photos.map { it.id })
        assertEquals(group.key, hidden.sourceKey)
        assertEquals(1L, hidden.recommendedId)

        val rest = SimilarGroup(listOf(shot(1), shot(2)), recommendedId = 1)
        val followed = compareSession(group.key, listOf(rest), emptySet())
        assertEquals(rest.key, followed!!.sourceKey)
        assertEquals(listOf(1L, 2L), followed.photos.map { it.id })
    }

    @Test
    fun onePhotoStaysUntilThatPhotoIsTrashed() {
        val group = SimilarGroup(listOf(shot(1), shot(2), shot(3)), recommendedId = 1)
        val stayed = compareSession(group.key, listOf(group), setOf(2L, 3L))
        assertEquals(listOf(1L), stayed!!.photos.map { it.id })
        assertEquals(3L, nextComparePhotoId(group.photos, 2L))
        assertEquals(2L, nextComparePhotoId(group.photos, 3L))
        assertEquals(1L, nextComparePhotoId(listOf(shot(1), shot(2)), 2L))
        assertNull(nextComparePhotoId(listOf(shot(1)), 1L))
        assertNull(compareSession(group.key, listOf(group), setOf(1L, 2L, 3L)))
    }

    @Test
    fun scanDropsTheGroupAndTheLastPhotoRemains() {
        val group = SimilarGroup(listOf(shot(1), shot(2), shot(3)), recommendedId = 1)
        val marks = mapOf(group.key to mapOf(1L to SimilarMark.Keep, 2L to SimilarMark.Remove, 3L to SimilarMark.Remove))
        val kept = soleRemainder(listOf(group), emptyList(), setOf(2L, 3L), marks, emptyMap())
        assertEquals(1L, kept.getValue(group.key).photo.id)
        assertEquals(SimilarMark.Keep, kept.getValue(group.key).mark)
        val session = compareSession(group.key, emptyList(), emptySet(), kept[group.key])
        assertEquals(listOf(1L), session!!.photos.map { it.id })
        assertNull(compareSession(group.key, emptyList(), setOf(1L), kept[group.key]))

        val smaller = SimilarGroup(listOf(shot(1), shot(2)), recommendedId = 1)
        val followed = soleRemainder(listOf(group), listOf(smaller), setOf(3L), marks, emptyMap())
        assertEquals(emptyMap<String, CompareRemainder>(), followed)
    }

    @Test
    fun lastPhotoStaysOnTheOriginalRouteAfterTheGroupKeyChanges() {
        val original = SimilarGroup(listOf(shot(1), shot(2), shot(3), shot(4)), recommendedId = 4)
        val pair = SimilarGroup(listOf(shot(3), shot(4)), recommendedId = 4)
        val marks = mapOf(pair.key to mapOf(3L to SimilarMark.Remove, 4L to SimilarMark.Keep))
        val kept = soleRemainder(listOf(pair), emptyList(), setOf(3L), marks, emptyMap())
        assertEquals(pair.key, kept.keys.single())
        val held = remainderForRoute(original.key, kept)
        assertEquals(SimilarMark.Keep, held!!.mark)
        val session = compareSession(original.key, emptyList(), setOf(1L, 2L, 3L), held)
        assertEquals(listOf(4L), session!!.photos.map { it.id })
    }

    @Test
    fun trashingTheRecommendedPhotoPicksTheNextSharpest() {
        val photos = listOf(shot(1), shot(2), shot(3))
        val group = SimilarGroup(photos, recommendedId = 1, sharpness = mapOf(1L to 1.0, 2L to 9.0, 3L to 4.0))
        val session = compareSession(group.key, listOf(group), setOf(1L))
        assertEquals(2L, session!!.recommendedId)
    }

    @Test
    fun marksMoveOntoTheGroupThatLostTheTrashedPhoto() {
        val old = SimilarGroup(listOf(shot(1), shot(2), shot(3), shot(4)), recommendedId = 1)
        val kept = mapOf(1L to SimilarMark.Keep, 2L to SimilarMark.Keep, 3L to SimilarMark.Remove, 4L to SimilarMark.Remove)
        val next = SimilarGroup(listOf(shot(1), shot(2), shot(3)), recommendedId = 1)
        val marks = migratedSimilarMarks(mapOf(old.key to kept), next)
        assertEquals(SimilarMark.Keep, marks!![2L])
        assertEquals(SimilarMark.Remove, marks[3L])
        assertNull(migratedSimilarMarks(emptyMap(), next))
    }

    private fun shot(id: Long) = TestPhotoData.createTestPhoto(id).copy(dateTaken = id * 1_000)
}
