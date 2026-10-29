package com.traveler.feature.map.threed

import org.junit.Assert.*
import org.junit.Test

class CameraQualityEnhancementsTest {
    @Test fun antipodalTransitionStaysOnSphereAndIsIndependentOfSeekOrder() {
        val a=Vec3(1.0,0.0,0.0); val b=Vec3(-1.0,0.0,0.0)
        val expected=CameraTransition.focus(a,b,.5)
        listOf(.1,.9,.2,.75,.0,1.0).forEach { t ->
            val p=CameraTransition.focus(a,b,t)
            assertEquals(1.0,p.length(),1e-6)
            assertTrue(p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
        }
        assertEquals(expected,CameraTransition.focus(a,b,.5))
        assertEquals(a,CameraTransition.focus(a,b,0.0)); assertEquals(b,CameraTransition.focus(a,b,1.0))
    }
    @Test fun thermalRecoveryWaitsAndBatteryModeCapsQualityAndFrameRate() {
        val q=RenderQuality()
        q.observe(10.0,true); assertEquals(.5f,q.scale,0f)
        repeat(300) { q.observe(10.0,false) }; assertEquals(.5f,q.scale,0f)
        repeat(300) { q.observe(10.0,false) }; assertTrue(q.scale>.5f)
        q.mode=QualityMode.BATTERY
        repeat(2000) { q.observe(1.0,false) }
        assertTrue(q.scale<=.65f); assertEquals(50L,q.frameIntervalMs)
        q.mode=QualityMode.DETAIL; q.observe(1.0,false,true)
        assertTrue(q.scale<=.65f); assertEquals(50L,q.frameIntervalMs)
    }
}
