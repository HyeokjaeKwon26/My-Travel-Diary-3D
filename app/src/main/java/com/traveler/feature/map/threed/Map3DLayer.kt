package com.traveler.feature.map.threed

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.traveler.core.terrain.*
import com.traveler.feature.map.renderer.*
import com.traveler.feature.map.story.TravelStoryTimeline
import kotlinx.coroutines.*

@Composable
fun Map3DLayer(model:TravelMapRenderModel,timeline:TravelStoryTimeline,state:TravelPlaybackState?, playing:Boolean=false,
               showOptions:Boolean=false, onDismissOptions:()->Unit={}, controlsBottomInset:Dp=112.dp,
               fallback:@Composable ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var sceneReady by remember { mutableStateOf(false) }
    var use3D by remember { mutableStateOf(true) }
    val preferences = remember { context.getSharedPreferences("scene_preferences", 0) }
    val calm=true // RC4 intentionally ignores the older orbit/follow-heading preference.
    val mapScale = 1.0
    var internetMaps by remember { mutableStateOf(preferences.getBoolean("internetMaps",true)) }
    var mapStatus by remember { mutableStateOf("Reference map · loading street detail") }
    val uriHandler=androidx.compose.ui.platform.LocalUriHandler.current
    var credits by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var packs by remember { mutableStateOf<List<TerrainPack>>(emptyList()) }
    var plan by remember(model) { mutableStateOf<TerrainJourneyPlan?>(null) }
    var terrainStatus by remember(model) { mutableStateOf(TerrainStatus()) }
    var storedBytes by remember { mutableStateOf(0L) }
    var pinned by remember { mutableStateOf(false) }
    var limitMb by remember { mutableStateOf((JourneyTerrain.budget(context)/1_000_000).toInt()) }
    val activePlayback by rememberUpdatedState(playing)
    LaunchedEffect(model) {
        try {
            val prepared=JourneyTerrain.prepare(context,model)
            plan=prepared;pinned=prepared.pinned
            packs=JourneyTerrain.load(context,prepared.key)
            var loaded=-1
            while(isActive) {
                terrainStatus=JourneyTerrain.status(context,prepared)
                storedBytes=withContext(Dispatchers.IO) { JourneyTerrain.store(context).bytes() }
                if(!activePlayback && loaded!=terrainStatus.ready) {
                    packs=JourneyTerrain.load(context,prepared.key);loaded=terrainStatus.ready
                }
                delay(2000)
            }
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { message=e.message;packs=TerrainRepository.load(context) }
    }
    fun action(block:suspend ()->Unit) { scope.launch {
        try { block() } catch(e:CancellationException) { throw e }
        catch(e:Exception) { message=e.message ?: "Could not update terrain" }
    } }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch {
            try { packs=TerrainRepository.importPack(context,uri);message="Terrain ready for offline playback" }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { message=e.message ?: "Could not import terrain pack" }
        }
    }
    val scene by produceState<SceneGeometry?>(null,model,timeline,packs) {
        value=withContext(Dispatchers.Default) { SceneGeometry(model,timeline,packs) }
    }
    Box(Modifier.fillMaxSize()) {
        if(use3D) {
            if(scene!=null) Travel3DSurface(scene!!,state,calm,Modifier.fillMaxSize(),mapScale,internetMaps,{mapStatus=it},{sceneReady=it}) {
                message=it;use3D=false
            } else CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else fallback()
        if(use3D && !sceneReady) Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Preparing map…",Modifier.padding(8.dp))
        }
        if (state?.currentSegment?.geometryProvenance == com.traveler.core.model.GeometryProvenance.CONTINUITY_ESTIMATE) Text("Estimated route",
            color=Color.White, fontSize=12.sp, modifier=Modifier.align(Alignment.CenterStart).padding(8.dp))
        if (scene?.uncertain(state) == true) Text("Height estimated",
            color=Color.White, fontSize=12.sp, modifier=Modifier.align(Alignment.Center)
                .background(Color(0xDD102638),RoundedCornerShape(8.dp)).padding(8.dp))
        if(use3D) Column(Modifier.align(Alignment.BottomStart).padding(start=6.dp,bottom=if (state != null) controlsBottomInset else 8.dp)
            .background(Color(0xDD102638),RoundedCornerShape(6.dp)).padding(horizontal=6.dp,vertical=3.dp)) {
            if(!mapStatus.startsWith("Street map")) Text(mapStatus,color=Color.White,fontSize=9.sp,lineHeight=11.sp)
            Text("N ↑ · © OpenStreetMap contributors · Natural Earth",color=Color.White,fontSize=9.sp,lineHeight=11.sp,
                modifier=Modifier.clickable { uriHandler.openUri("https://www.openstreetmap.org/copyright") }.padding(vertical=2.dp))
        }
    }
    if(showOptions) AlertDialog(onDismissRequest=onDismissOptions,title={Text("3D map & terrain")},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(terrainStatus.message)
            Text("Terrain is prepared automatically on Wi-Fi. Saved terrain works offline. Your Timeline and photos stay on this phone.",fontSize=12.sp)
            plan?.let { p ->
                Text("Estimated initial download: ${(p.estimatedDownloadBytes/1_000_000.0).let { "%.1f".format(it) }} MB; actual size varies.",fontSize=11.sp)
                if(terrainStatus.ready<terrainStatus.total) {
                    TextButton(onClick={action { JourneyTerrain.resume(context,p.key,true) }}) { Text("Download / retry using mobile data too") }
                    TextButton(onClick={action { JourneyTerrain.resume(context,p.key,false) }}) { Text("Prepare on Wi-Fi") }
                    if(terrainStatus.busy) TextButton(onClick={action { JourneyTerrain.pause(context,p.key) }}) { Text("Pause download") }
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Keep this journey offline",Modifier.weight(1f),fontSize=13.sp)
                    Switch(checked=pinned,onCheckedChange={ value -> action { JourneyTerrain.pin(context,p.key,value);pinned=value } })
                }
            }
            Text("Terrain storage: %.1f MB / %d MB".format(storedBytes/1_000_000.0,limitMb),fontSize=12.sp)
            Row { listOf(100,200,500).forEach { mb -> TextButton(onClick={action { JourneyTerrain.setBudget(context,mb);limitMb=mb }}) { Text("$mb MB") } } }
            TextButton(onClick={action { plan?.let { JourneyTerrain.pause(context,it.key) };JourneyTerrain.clearTemporary(context);plan?.let { packs=JourneyTerrain.load(context,it.key) } }}) { Text("Clear temporary terrain") }
            TextButton(onClick={use3D=!use3D}) { Text(if(use3D) "Switch to 2D map" else "Switch to 3D map") }
            Text("Camera: north up. The map follows your location without rotating or arrival zooms.",fontSize=12.sp)
            Text("Automatic route framing · no pinch zoom. The same framing is used in your video.",fontSize=12.sp)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Internet street detail",Modifier.weight(1f),fontSize=13.sp)
                Switch(checked=internetMaps,onCheckedChange={internetMaps=it;preferences.edit().putBoolean("internetMaps",it).apply()})
            }
            Text("Roads and place names load for the visible screen only, using Wi-Fi or mobile data. Viewed tiles are cached (up to 96 MB). Unseen offline areas use the reference map. Video uses cached detail without downloading new map areas.",fontSize=11.sp)
            TextButton(onClick={picker.launch(arrayOf("application/json","application/octet-stream"))}) { Text("Advanced: import terrain file") }
            TextButton(onClick={action { TerrainRepository.clearImported(context);packs=plan?.let { JourneyTerrain.load(context,it.key) } ?: TerrainRepository.load(context) }}) { Text("Remove imported terrain packs") }
            Text("Terrain: Mapzen / USGS and regional contributors. Tile requests reveal the requested area and IP to the provider; photos and Timeline files are never uploaded.",fontSize=11.sp)
            TextButton(onClick={credits=true}) { Text("Terrain data sources & credits") }
            Text("Outside stored regions the globe has no detailed relief. Terrain height is estimated, not measured vehicle altitude.",fontSize=11.sp)
            Text("Street map: © OpenStreetMap contributors. Reference map: Natural Earth. Map requests reveal the visible area and your IP to OpenStreetMap; Timeline files and photos stay local.",fontSize=11.sp)
            TextButton(onClick={uriHandler.openUri("https://www.openstreetmap.org/fixthemap")}) { Text("Report a map issue") }
            message?.let { Text(it,fontSize=11.sp) }
        }
    },confirmButton={TextButton(onClick=onDismissOptions) { Text("Done") }})
    if(credits) {
        val text by produceState("") { value=withContext(Dispatchers.IO) { context.assets.open("terrain_attribution.md").bufferedReader().use { it.readText() } } }
        AlertDialog(onDismissRequest={credits=false},title={Text("Terrain credits")},
            text={Text("Mapzen Terrain Tiles. Grids resampled to 65 × 65 and rounded to metres; tile edges averaged. No agency endorsement.\n\n"+text,
                modifier=Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),fontSize=11.sp)},
            confirmButton={TextButton(onClick={credits=false}) { Text("Done") }})
    }

}
