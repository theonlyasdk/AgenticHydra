package app.agentichydra

import android.content.res.Configuration
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.random.Random

data class HydraOrbitUiState(val visibleSatellites:Set<Int> = setOf(0,1,2))

@Composable fun HydraIllustration(modifier:Modifier=Modifier) {
    val loop=rememberInfiniteTransition(label="Hydra orbit")
    val phase=loop.animateFloat(0f,360f,infiniteRepeatable(tween(24_000,easing=LinearEasing)),label="Orbit angle")
    val pulse=loop.animateFloat(.94f,1.06f,infiniteRepeatable(tween(2200,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="Hydra pulse")
    var state by remember { mutableStateOf(HydraOrbitUiState()) }
    LaunchedEffect(Unit) {
        val random=Random(System.nanoTime())
        while(true) {
            delay(random.nextLong(3500,6500))
            val index=random.nextInt(4)
            val visible=state.visibleSatellites
            state=state.copy(visibleSatellites=if(index in visible&&visible.size>1)visible-index else visible+index)
        }
    }
    HydraOrbitIllustration(state,{phase.value},{pulse.value},modifier)
}

@Composable fun HydraOrbitIllustration(state:HydraOrbitUiState,phase:()->Float,pulse:()->Float,modifier:Modifier=Modifier) {
  HydraOrbitTheme {
    val cs=MaterialTheme.colorScheme
    val icons=listOf(Icons.Outlined.Code,Icons.Outlined.Terminal,Icons.Outlined.AutoAwesome,Icons.Outlined.StarOutline)
    val diameters=listOf(24.dp,28.dp,20.dp,24.dp)
    val iconSizes=listOf(16.dp,18.dp,14.dp,16.dp)
    val fills=listOf(cs.primaryContainer,cs.secondaryContainer,cs.tertiaryContainer,cs.surfaceContainerHigh)
    val tints=listOf(cs.primary,cs.secondary,cs.tertiary,cs.onSurfaceVariant)
    val reveals=List(icons.size) { index ->
        animateFloatAsState(if(index in state.visibleSatellites)1f else 0f,
            animationSpec=spring(dampingRatio=Spring.DampingRatioNoBouncy,stiffness=Spring.StiffnessMediumLow),label="Satellite $index")
    }
    BoxWithConstraints(modifier,contentAlignment=Alignment.Center) {
        val density=LocalDensity.current
        val radius=with(density){maxWidth.toPx()*.38f}
        val sizes=diameters.map { with(density){it.toPx()} }
        val dotSize=with(density){4.dp.toPx()}
        fun position(index:Int,phase:Float):Offset {
            val angle=Math.toRadians((phase*(if(index%2==0)1f else -.65f)+index*90f).toDouble())
            return Offset(cos(angle).toFloat()*radius,sin(angle).toFloat()*radius)
        }
        fun diameter(index:Int):Float {
            val dot=dotSize*(1f+(pulse()-1f)*4f)
            return dot+(sizes[index]-dot)*reveals[index].value
        }
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(cs.primaryContainer,Color.Transparent),radius=size.minDimension*.48f*pulse()),radius=size.minDimension*.48f*pulse())
            drawCircle(cs.outlineVariant,radius=size.minDimension*.38f,style=Stroke(1.dp.toPx()))
        }
        HydraMark(Modifier.fillMaxSize(.42f).graphicsLayer {scaleX=pulse();scaleY=pulse()})
        icons.forEachIndexed { index,icon ->
            Surface(color=fills[index],shape=MaterialTheme.shapes.large,modifier=Modifier.size(diameters[index]).graphicsLayer {
                val angle=phase()
                val center=position(index,angle)
                translationX=center.x;translationY=center.y
                val ownDiameter=diameter(index)
                scaleX=ownDiameter/sizes[index];scaleY=scaleX
                var overlap=0f
                icons.indices.filter{it!=index}.forEach { other ->
                    val distance=(center-position(other,angle)).getDistance()
                    val touchingDistance=(ownDiameter+diameter(other))*.5f
                    overlap=max(overlap,(1f-distance/touchingDistance).coerceIn(0f,1f))
                }
                // Real overlap opacity: both orbiters remain visible as they cross.
                alpha=1f-overlap*.55f
            }) {
                Box(contentAlignment=Alignment.Center) {
                    // A colored dot is always present; only the glyph fades during its expansion.
                    Icon(icon,null,Modifier.size(iconSizes[index]).graphicsLayer{alpha=reveals[index].value},tint=tints[index])
                }
            }
        }
    }
  }
}

@Preview(name="Hydra orbit light")
@Preview(name="Hydra orbit dark",uiMode=Configuration.UI_MODE_NIGHT_YES)
@Preview(name="Hydra orbit large text",fontScale=1.5f)
@Composable private fun OrbitPreview(){HydraTheme{Surface{HydraOrbitIllustration(HydraOrbitUiState(),{30f},{1f},Modifier.size(112.dp))}}}
