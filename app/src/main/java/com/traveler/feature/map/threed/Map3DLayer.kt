package com.traveler.feature.map.threed

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.sp
import com.traveler.core.terrain.*
import com.traveler.feature.map.renderer.*
import com.traveler.feature.map.story.TravelStoryTimeline
import kotlinx.coroutines.*

@Composable
fun Map3DLayer(model:TravelMapRenderModel,timeline:TravelStoryTimeline,state:TravelPlaybackState?, playing:Boolean=false,
               fallback:@Composable ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var use3D by remember { mutableStateOf(true) }
    val preferences = remember { context.getSharedPreferences("scene_preferences", 0) }
    var calm by remember { mutableStateOf(preferences.getBoolean("calm", false)) }
    var options by remember { mutableStateOf(false) }
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
            if(scene!=null) Travel3DSurface(scene!!,state,calm,Modifier.fillMaxSize()) {
                message=it;use3D=false
            } else CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else fallback()
        if (scene?.uncertain(state) == true) Text("Elevation uncertain • vehicle hidden",
            color=Color.White, fontSize=12.sp, modifier=Modifier.align(Alignment.Center)
                .background(Color(0xDD102638),RoundedCornerShape(8.dp)).padding(8.dp))
        if(terrainStatus.ready<terrainStatus.total || plan?.limited==true) Text(
            terrainStatus.message,color=Color.White,fontSize=10.sp,
            modifier=Modifier.align(Alignment.BottomStart).padding(start=8.dp,bottom=62.dp)
                .background(Color(0xCC102638),RoundedCornerShape(6.dp)).padding(5.dp))
        TextButton(onClick={options=true},modifier=Modifier.align(Alignment.TopEnd).padding(4.dp)
            .background(Color(0xDD102638),RoundedCornerShape(12.dp))) {
            Text(if(use3D) "3D • Terrain" else "2D • Options",color=Color.White,fontSize=11.sp)
        }
    }
    if(options) AlertDialog(onDismissRequest={options=false},title={Text("3D map & terrain")},text={
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
            TextButton(onClick={calm=!calm;preferences.edit().putBoolean("calm",calm).apply()}) { Text(if(calm) "Camera: steady north" else "Camera: follow journey") }
            TextButton(onClick={picker.launch(arrayOf("application/json","application/octet-stream"))}) { Text("Advanced: import terrain file") }
            TextButton(onClick={action { TerrainRepository.clearImported(context);packs=plan?.let { JourneyTerrain.load(context,it.key) } ?: TerrainRepository.load(context) }}) { Text("Remove imported terrain packs") }
            Text("Terrain: Mapzen / USGS and regional contributors. Tile requests reveal the requested area and IP to the provider; photos and Timeline files are never uploaded.",fontSize=11.sp)
            TextButton(onClick={credits=true}) { Text("Terrain data sources & credits") }
            Text("Outside stored regions the globe has no detailed relief. Terrain height is estimated, not measured vehicle altitude.",fontSize=11.sp)
            Text("Offline map: Natural Earth. Regional roads, rivers and place names are included; this is not a street-level navigation map.",fontSize=11.sp)
            message?.let { Text(it,fontSize=11.sp) }
        }
    },confirmButton={TextButton(onClick={options=false}) { Text("Done") }})
    if(credits) {
        val text by produceState("") { value=withContext(Dispatchers.IO) { context.assets.open("terrain_attribution.md").bufferedReader().use { it.readText() } } }
        AlertDialog(onDismissRequest={credits=false},title={Text("Terrain credits")},
            text={Text("Mapzen Terrain Tiles. Grids resampled to 65 × 65 and rounded to metres; tile edges averaged. No agency endorsement.\n\n"+text,
                modifier=Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),fontSize=11.sp)},
            confirmButton={TextButton(onClick={credits=false}) { Text("Done") }})
    }

}
