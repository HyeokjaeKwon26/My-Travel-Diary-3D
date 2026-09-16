package com.traveler.feature.map.threed

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
fun Map3DLayer(model:TravelMapRenderModel,timeline:TravelStoryTimeline,state:TravelPlaybackState?,
               fallback:@Composable ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var use3D by remember { mutableStateOf(true) }
    val preferences = remember { context.getSharedPreferences("scene_preferences", 0) }
    var calm by remember { mutableStateOf(preferences.getBoolean("calm", false)) }
    var options by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var packs by remember { mutableStateOf<List<TerrainPack>>(emptyList()) }
    LaunchedEffect(Unit) {
        try { packs=TerrainRepository.load(context) }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { message=e.message }
    }
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
        TextButton(onClick={options=true},modifier=Modifier.align(Alignment.TopEnd).padding(4.dp)
            .background(Color(0xDD102638),RoundedCornerShape(12.dp))) {
            Text(if(use3D) "3D • Terrain" else "2D • Options",color=Color.White,fontSize=11.sp)
        }
    }
    if(options) AlertDialog(onDismissRequest={options=false},title={Text("3D map & terrain")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Regional terrain stays on this phone. Playback needs no internet connection.")
            TextButton(onClick={use3D=!use3D}) { Text(if(use3D) "Switch to 2D map" else "Switch to 3D map") }
            TextButton(onClick={calm=!calm;preferences.edit().putBoolean("calm",calm).apply()}) { Text(if(calm) "Camera: steady north" else "Camera: follow journey") }
            TextButton(onClick={picker.launch(arrayOf("application/json","application/octet-stream"))}) { Text("Import terrain pack") }
            if(packs.size>1) TextButton(onClick={scope.launch { packs=TerrainRepository.clearImported(context) }}) { Text("Remove imported terrain packs") }
            packs.forEach { Text(it.name+"\n"+it.attribution,fontSize=11.sp) }
            Text("Outside stored regions the globe has no detailed relief. Terrain height is estimated, not measured vehicle altitude.",fontSize=11.sp)
            message?.let { Text(it,fontSize=11.sp) }
        }
    },confirmButton={TextButton(onClick={options=false}) { Text("Done") }})
}
