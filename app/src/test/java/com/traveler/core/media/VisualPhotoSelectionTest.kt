package com.traveler.core.media

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.*
import org.junit.Assert.*
import org.junit.Test

class VisualPhotoSelectionTest {
    private fun photo(id: String, features: PhotoVisualFeatures?=null, pinned: Boolean=false)=MediaItem(
        id,"content://photos/$id","photo.jpg","image/jpeg",1000,TimestampConfidence.EXIF_EXACT,
        matchedVisitId="v",isRepresentative=pinned,visualFeatures=features)
    @Test fun visualContentRequiredBeforeCollapsingAPhoto() {
        assertEquals(2,RepresentativeMediaSelector.collapseBursts(listOf(photo("a"),photo("b"))).size)
        val f=PhotoVisualFeatures(10L,200f,.5f,listOf(.1f,.9f))
        assertEquals(1,RepresentativeMediaSelector.collapseBursts(listOf(photo("a",f),photo("b",f))).size)
        assertEquals(2,RepresentativeMediaSelector.collapseBursts(listOf(photo("a",f,true),photo("b",f,true))).size)
        assertEquals(2,RepresentativeMediaSelector.collapseBursts(listOf(photo("a",f),photo("b",f.copy(hash=Long.MAX_VALUE)))).size)
    }
    @Test fun sharpImageOutranksBlurButUserChoiceWins() {
        val sharp=IntArray(64*64) { if((it%64/4+it/64/4)%2==0) -1 else 0xff202020.toInt() }
        val blur=IntArray(64*64) { 0xff777777.toInt() }
        val a=PhotoVisualMath.extract(sharp,64,64);val b=PhotoVisualMath.extract(blur,64,64)
        assertTrue(a.sharpness>b.sharpness)
        assertTrue(RepresentativeMediaSelector.scorePhoto(photo("a",a))>RepresentativeMediaSelector.scorePhoto(photo("b",b)))
        assertTrue(RepresentativeMediaSelector.scorePhoto(photo("b",b,true))>RepresentativeMediaSelector.scorePhoto(photo("a",a)))
    }
    @Test fun everyEligiblePinSurvivesAutomaticStoryBudgetAndFilenameRules() {
        val visit=Visit("v",location=GeoPoint(36.0,-112.0),startTimestampEpochMs=0,endTimestampEpochMs=100000,confidence=1f)
        val photos=(0..19).map { photo("$it",pinned=true).copy(fileName="download_$it.jpg") }
        val moments=PhotoStoryEngine.buildGlobalPhotoStoryMoments(listOf(visit),emptyList(),photos,StoryDurationProfile.SHORT)
        assertEquals(20,moments.size)
        val model=com.traveler.feature.map.renderer.TravelMapRenderModel(listOf(visit),emptyList(),photos)
        val timeline=com.traveler.feature.map.story.TravelStoryTimeline.build(model,StoryDurationProfile.SHORT)
        assertEquals(20,timeline.photoMoments.size)
        assertTrue(timeline.photoMoments.all { it.displayEndStorySeconds>it.displayStartStorySeconds })
    }
}
