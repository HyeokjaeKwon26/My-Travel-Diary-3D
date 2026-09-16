package com.traveler.feature.map.threed

import com.traveler.core.model.TransportMode
import kotlin.math.*

/** Small procedural toys: +Z is always the nose; no bitmap/model downloads. */
object ToyVehicle {
    private val ink=floatArrayOf(.055f,.12f,.20f,1f)
    private val white=floatArrayOf(1f,.98f,.89f,1f)
    private val glass=floatArrayOf(.15f,.73f,.94f,1f)
    private val gold=floatArrayOf(1f,.66f,.08f,1f)
    private val red=floatArrayOf(.98f,.22f,.22f,1f)
    private val blue=floatArrayOf(.15f,.40f,.95f,1f)
    private val green=floatArrayOf(.18f,.82f,.49f,1f)
    private val skin=floatArrayOf(1f,.75f,.46f,1f)

    fun mesh(mode:TransportMode,phase:Double):FloatArray {
        val b=Builder()
        fun p(x:Double,y:Double,z:Double)=Vec3(x,y,z)
        fun box(x:Double,y:Double,z:Double,w:Double,h:Double,l:Double,c:FloatArray)=b.box(p(x,y,z),p(w,h,l),c)
        fun wheel(x:Double,z:Double,r:Double=.43)=b.wheel(p(x,r,z),r,phase)
        fun face(z:Double,y:Double,width:Double=.46) {
            for(x in listOf(-width,width)) {
                b.ball(p(x,y,z),p(.20,.22,.11),white)
                b.ball(p(x,y,z+.08),p(.095,.12,.07),ink)
            }
        }
        when(mode) {
            TransportMode.CAR -> {
                box(0.0,.67,0.0,1.8,.65,2.8,gold)
                box(0.0,1.25,-.15,1.45,.65,1.6,red)
                box(0.0,1.28,.68,1.22,.43,.05,glass)
                box(0.0,1.28,-.98,1.22,.38,.05,glass)
                for(x in listOf(-.74,.74)) box(x,1.28,-.15,.04,.40,1.24,glass)
                box(0.0,.52,1.43,1.6,.17,.16,white)
                face(1.44,.86)
                for(x in listOf(-.95,.95)) for(z in listOf(-.85,.85)) wheel(x,z)
            }
            TransportMode.TRAIN -> {
                // KTX-inspired streamlined power car + articulated passenger coach. +Z is the nose.
                for (z in listOf(.4, -2.15)) {
                    box(0.0,.40,z,1.15,.25,2.25,ink)
                    box(0.0,.94,z,1.28,.91,2.25,white)
                    box(0.0,1.44,z,1.15,.16,2.22,white)
                    for (x in listOf(-.65,.65)) {
                        box(x,.72,z,.025,.22,2.20,blue)
                        for (w in listOf(-.72,-.24,.24,.72)) box(x,1.14,z+w,.035,.31,.33,ink)
                    }
                    for (x in listOf(-.60,.60)) for (w in listOf(-.76,.76)) wheel(x,z+w,.22)
                }
                box(0.0,.86,-.89,.88,.67,.30,ink) // flexible gangway
                // Elliptical cross sections taper down into the long, low bullet nose.
                val sections = listOf(doubleArrayOf(1.50,.95,.64,.52), doubleArrayOf(1.95,.87,.58,.44),
                    doubleArrayOf(2.45,.69,.40,.29), doubleArrayOf(2.93,.56,.12,.12))
                for (i in 0 until sections.lastIndex) for (j in 0 until 12) {
                    fun ring(k:Int,n:Int):Vec3 {
                        val q=sections[k]; val a=n*PI/6
                        return p(cos(a)*q[2],q[1]+sin(a)*q[3],q[0])
                    }
                    val c=if(j>=6) blue else white
                    b.tri(ring(i,j),ring(i+1,j),ring(i+1,j+1),c)
                    b.tri(ring(i,j),ring(i+1,j+1),ring(i,j+1),c,.88f)
                }
                b.ball(p(0.0,.56,2.93),p(.12,.12,.13),blue)
                // Swept panoramic windshield, bright headlights, roof pantograph.
                b.wedge(p(-.45,1.37,1.67),p(.45,1.37,1.67),p(0.0,1.05,2.18),.045,ink)
                for(x in listOf(-.25,.25)) b.ball(p(x,.71,2.55),p(.09,.055,.065),white)
                b.rod(p(-.35,1.54,-.1),p(0.0,1.87,-.36),.035,ink)
                b.rod(p(0.0,1.87,-.36),p(.35,1.54,-.1),.035,ink)
                box(0.0,1.89,-.36,.85,.055,.12,ink)
            }
            TransportMode.BUS,TransportMode.SUBWAY -> {
                val train=mode!=TransportMode.BUS
                val color=if(mode==TransportMode.BUS)red else if(mode==TransportMode.TRAIN)blue else green
                box(0.0,.52,0.0,1.95,.42,3.6,ink)
                box(0.0,1.25,0.0,1.85,1.25,3.6,color)
                box(0.0,1.94,-.1,1.9,.18,3.35,white)
                box(0.0,1.38,1.83,1.55,.67,.06,glass)
                for(x in listOf(-.94,.94)) for(z in listOf(-1.15,-.35,.45))
                    box(x,1.48,z,.04,.51,.58,glass)
                for(x in listOf(-1.0,1.0)) for(z in if(train)listOf(-1.2,0.0,1.2) else listOf(-1.1,1.1)) wheel(x,z,.34)
                face(1.88,.83,.55)
                if(train) { box(0.0,2.12,-.65,.9,.25,.8,gold);box(0.0,.46,1.94,1.9,.25,.30,white) }
            }
            TransportMode.AIRPLANE -> {
                // Solid fuselage and thick wings remain visible when viewed edge-on.
                box(0.0,.68,-.1,.70,.63,3.35,white)
                b.ball(p(0.0,.68,1.74),p(.36,.32,.58),gold)
                box(0.0,1.01,.85,.50,.08,.65,glass)
                for(sign in listOf(-1.0,1.0)) {
                    b.wedge(p(sign*.22,.65,.8),p(sign*2.65,.65,-.40),p(sign*.25,.65,-.75),.16,red)
                    b.wedge(p(sign*.16,.83,-1.18),p(sign*1.13,.83,-1.92),p(sign*.16,.83,-1.93),.12,gold)
                    box(sign*1.02,.44,-.25,.30,.35,.85,ink)
                }
                b.wedge(p(0.0,.8,-1.10),p(0.0,1.75,-1.93),p(0.0,.8,-1.95),.10,blue,sideways=true)
                box(0.0,.72,2.15,.22,.18,.16,red)
            }
            TransportMode.FERRY -> {
                box(0.0,.35,-.30,2.0,.57,2.8,blue)
                b.wedge(p(-1.0,.60,1.1),p(0.0,.60,2.1),p(1.0,.60,1.1),.55,blue)
                box(0.0,.72,-.25,2.08,.15,2.9,white)
                box(0.0,1.14,-.42,1.32,.72,1.55,white)
                box(0.0,1.26,.39,1.1,.34,.05,glass)
                box(0.0,1.75,-.8,.5,.65,.48,red)
                for(x in listOf(-.7,.7)) for(z in listOf(-.95,-.4,.15)) b.ball(p(x,1.15,z),p(.06,.15,.15),glass)
                b.wedge(p(-.5,.02,-1.8),p(-1.1,.02,-2.2),p(-.7,.02,-1.25),.03,white)
                b.wedge(p(.5,.02,-1.8),p(1.1,.02,-2.2),p(.7,.02,-1.25),.03,white)
            }
            TransportMode.WALK,TransportMode.RUN,TransportMode.BICYCLE -> {
                val bike=mode==TransportMode.BICYCLE
                val run=mode==TransportMode.RUN
                val hip=if(bike)1.28 else 1.12
                if(bike) {
                    wheel(0.0,-1.05,.54);wheel(0.0,1.05,.54)
                    for((a,c) in listOf(p(0.0,.55,-1.05) to p(0.0,1.25,-.3),p(0.0,1.25,-.3) to p(0.0,.6,.2),
                        p(0.0,.6,.2) to p(0.0,.55,-1.05),p(0.0,1.25,-.3) to p(0.0,1.3,.65),
                        p(0.0,1.3,.65) to p(0.0,.55,1.05),p(0.0,.6,.2) to p(0.0,1.3,.65))) b.rod(a,c,.10,green)
                    box(0.0,1.4,-.3,.6,.13,.50,ink);box(0.0,1.55,.70,.85,.12,.15,ink)
                }
                box(0.0,hip+.40,if(bike).05 else 0.0,.75,.8,.50,if(run)red else blue)
                b.ball(p(0.0,hip+1.08,if(bike).22 else .06),p(.40,.43,.37),skin)
                b.ball(p(0.0,hip+1.34,if(bike).18 else .04),p(.43,.23,.40),gold)
                face(if(bike).55 else .39,hip+1.12,.15)
                for(sign in listOf(-1.0,1.0)) {
                    val swing=sin(phase+if(sign>0)0.0 else PI)
                    val foot=if(bike)p(sign*.25,.68+.26*cos(phase+if(sign>0)0.0 else PI),.2+.3*swing)
                        else p(sign*.23,.13+max(0.0,swing)*.28,swing*(if(run).8 else .52))
                    val knee=p(sign*.23,hip*.57,foot.z*.65+.20)
                    b.rod(p(sign*.23,hip,0.0),knee,.18,ink);b.rod(knee,foot,.16,ink)
                    b.box(foot+p(0.0,0.0,.08),p(.33,.20,.51),white)
                    val hand=if(bike)p(sign*.43,1.55,.70) else p(sign*.54,hip+.20,-swing*.65)
                    b.rod(p(sign*.4,hip+.65,0.0),hand,.16,if(run)red else blue)
                    b.ball(hand,p(.18,.18,.18),skin)
                }
            }
            TransportMode.UNKNOWN -> {
                b.ball(p(0.0,1.25,0.0),p(.85,.88,.65),red)
                b.wedge(p(-.48,.94,0.0),p(0.0,0.0,0.0),p(.48,.94,0.0),.18,red)
                b.ball(p(0.0,1.35,.58),p(.37,.38,.15),white)
            }
        }
        return b.data.toFloatArray()
    }

    private class Builder {
        val data=ArrayList<Float>(10000)
        fun tri(a:Vec3,b:Vec3,c:Vec3,color:FloatArray,shade:Float=1f) {
            for(p in listOf(a,b,c)) { data.add(p.x.toFloat());data.add(p.y.toFloat());data.add(p.z.toFloat())
                data.add(color[0]*shade);data.add(color[1]*shade);data.add(color[2]*shade);data.add(1f) }
        }
        fun box(center:Vec3,size:Vec3,color:FloatArray) {
            val p=listOf(Vec3(-1.0,-1.0,-1.0),Vec3(1.0,-1.0,-1.0),Vec3(1.0,-1.0,1.0),Vec3(-1.0,-1.0,1.0),
                Vec3(-1.0,1.0,-1.0),Vec3(1.0,1.0,-1.0),Vec3(1.0,1.0,1.0),Vec3(-1.0,1.0,1.0))
                .map { center+Vec3(it.x*size.x/2,it.y*size.y/2,it.z*size.z/2) }
            for((i,f) in listOf(listOf(0,1,5,4),listOf(1,2,6,5),listOf(2,3,7,6),listOf(3,0,4,7),listOf(4,5,6,7),listOf(0,3,2,1)).withIndex()) {
                val shade=listOf(.72f,.82f,.96f,.88f,1f,.62f)[i]
                tri(p[f[0]],p[f[1]],p[f[2]],color,shade);tri(p[f[0]],p[f[2]],p[f[3]],color,shade)
            }
        }
        fun ball(c:Vec3,r:Vec3,color:FloatArray) {
            val ring=(0..7).map { i->c+Vec3(cos(i*PI/4)*r.x,0.0,sin(i*PI/4)*r.z) }
            for(i in ring.indices) {
                tri(c+Vec3(0.0,r.y,0.0),ring[i],ring[(i+1)%8],color,.85f+.15f*(i%3)/2)
                tri(c-Vec3(0.0,r.y,0.0),ring[(i+1)%8],ring[i],color,.72f)
            }
        }
        fun wedge(a:Vec3,b:Vec3,c:Vec3,thickness:Double,color:FloatArray,sideways:Boolean=false) {
            val d=if(sideways)Vec3(thickness/2,0.0,0.0) else Vec3(0.0,thickness/2,0.0)
            tri(a+d,b+d,c+d,color);tri(c-d,b-d,a-d,color,.72f)
            for((p,q) in listOf(a to b,b to c,c to a)) { tri(p-d,q-d,q+d,color,.82f);tri(p-d,q+d,p+d,color,.82f) }
        }
        fun rod(a:Vec3,b:Vec3,r:Double,color:FloatArray) {
            val v=(b-a).unit();val side=v.cross(if(abs(v.y)<.9)Vec3(0.0,1.0,0.0) else Vec3(1.0,0.0,0.0)).unit()*r
            val other=v.cross(side).unit()*r
            val offsets=listOf(side,other,side*-1.0,other*-1.0)
            for(i in offsets.indices) { val x=offsets[i];val y=offsets[(i+1)%4]
                tri(a+x,b+x,b+y,color);tri(a+x,b+y,a+y,color,.85f) }
        }
        fun wheel(c:Vec3,r:Double,phase:Double) {
            for(i in 0 until 12) {
                val a=i*PI/6+phase;val b=(i+1)*PI/6+phase
                val p=Vec3(0.0,cos(a)*r,sin(a)*r);val q=Vec3(0.0,cos(b)*r,sin(b)*r)
                val d=Vec3(.12,0.0,0.0)
                tri(c+p-d,c+q-d,c+q+d,ink);tri(c+p-d,c+q+d,c+p+d,ink)
                for(sign in listOf(-1.0,1.0)) {
                    tri(c+d*sign,c+p+d*sign,c+q+d*sign,ink)
                    tri(c+d*(sign*1.05),c+p*.57+d*(sign*1.05),c+q*.57+d*(sign*1.05),if(i%3==0)gold else white)
                }
            }
        }
    }
}
