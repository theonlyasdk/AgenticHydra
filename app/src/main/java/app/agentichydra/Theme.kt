package app.agentichydra

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.os.Build
import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.*
import android.content.Context
import android.content.SharedPreferences

enum class AppThemeMode(val label:String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }
data class AppearanceUiState(val themeMode:AppThemeMode=AppThemeMode.SYSTEM,val dynamicColor:Boolean=false,val marquee:Boolean=true)

/** True when overflowing titles may auto-scroll; provided by [HydraTheme]. */
val LocalMarqueeEnabled = staticCompositionLocalOf { true }

@Composable fun rememberAppearanceState(): AppearanceUiState {
    val prefs=LocalContext.current.getSharedPreferences("appearance",Context.MODE_PRIVATE)
    fun read()=AppearanceUiState(
        runCatching{AppThemeMode.valueOf(prefs.getString("mode","SYSTEM")?:"SYSTEM")}.getOrDefault(AppThemeMode.SYSTEM),
        prefs.getBoolean("dynamic",false),
        prefs.getBoolean("marquee",true))
    var state by remember(prefs) { mutableStateOf(read()) }
    DisposableEffect(prefs){
        val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,_->state=read()}
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose{prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    return state
}

val InstrumentSans = FontFamily(Font(R.font.instrument_sans))
val InstrumentSerif = FontFamily(Font(R.font.instrument_serif))
val Typography.code:TextStyle get()=bodyMedium.copy(fontFamily=FontFamily.Monospace)
val Typography.hydraTitle:TextStyle get()=titleLarge.copy(fontFamily=InstrumentSans,fontWeight=FontWeight.SemiBold,fontSize=25.sp,letterSpacing=(-1).sp)
val Typography.markdownHeadings:List<TextStyle> get()=listOf(headlineMedium,
    titleLarge.copy(fontWeight=FontWeight.SemiBold),titleMedium.copy(fontWeight=FontWeight.SemiBold),
    bodyLarge.copy(fontWeight=FontWeight.SemiBold),bodyMedium.copy(fontWeight=FontWeight.SemiBold),labelMedium.copy(fontWeight=FontWeight.SemiBold))
private val Light = lightColorScheme(
    primary = Color(0xFF006B75), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EEF0), onPrimaryContainer = Color(0xFF174B52),
    secondary = Color(0xFF006B75), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5EEF0), onSecondaryContainer = Color(0xFF174B52),
    tertiary = Color(0xFF006B75), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5EEF0), onTertiaryContainer = Color(0xFF174B52),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F4F5),
    surfaceContainerHigh = Color(0xFFE7EEF2), surfaceContainerHighest = Color(0xFFE0E9EC),
    surfaceTint = Color(0xFF006B75), outlineVariant = Color(0xFFD2DDE0),
    background = Color(0xFFF3F7FA), onBackground = Color(0xFF182630),
    surface = Color(0xFFF3F7FA), onSurface = Color(0xFF182630),
    surfaceVariant = Color(0xFFE7EEF2), onSurfaceVariant = Color(0xFF536570),
    surfaceContainer = Color(0xFFFFFFFF), outline = Color(0xFF70818B),
    error = Color(0xFFAA3434), errorContainer = Color(0xFFFBE5E3), onErrorContainer = Color(0xFF752020)
)
private val Dark = darkColorScheme(
    inverseSurface = Color.White,
    primary = Color(0xFF80D5DE), onPrimary = Color(0xFF00363C),
    primaryContainer = Color(0xFF173A41), onPrimaryContainer = Color(0xFFBDECF0),
    secondary = Color(0xFF80D5DE), onSecondary = Color(0xFF00363C),
    secondaryContainer = Color(0xFF173A41), onSecondaryContainer = Color(0xFFBDECF0),
    tertiary = Color(0xFF80D5DE), onTertiary = Color(0xFF00363C),
    tertiaryContainer = Color(0xFF173A41), onTertiaryContainer = Color(0xFFBDECF0),
    surfaceContainerLowest = Color(0xFF0C1318), surfaceContainerLow = Color(0xFF142027),
    surfaceContainerHigh = Color(0xFF202F38), surfaceContainerHighest = Color(0xFF283A43),
    surfaceTint = Color(0xFF80D5DE), outlineVariant = Color(0xFF34464F),
    background = Color(0xFF10191F), onBackground = Color(0xFFDFEAF0),
    surface = Color(0xFF10191F), onSurface = Color(0xFFDFEAF0),
    surfaceVariant = Color(0xFF202F38), onSurfaceVariant = Color(0xFFABBDC8),
    surfaceContainer = Color(0xFF19262E), outline = Color(0xFF899CA8),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF512727), onErrorContainer = Color(0xFFFFDAD6)
)
// Decorative orbit accents stay local to the illustration, preserving the app's teal seed.
private val OrbitLight = Light.copy(secondary=Color(0xFF405F91),secondaryContainer=Color(0xFFD6E3FF),
    tertiary=Color(0xFF805600),tertiaryContainer=Color(0xFFFFDEA6))
private val OrbitDark = Dark.copy(secondary=Color(0xFFA8C8FF),secondaryContainer=Color(0xFF263F68),
    tertiary=Color(0xFFF3BF62),tertiaryContainer=Color(0xFF594117))

@Composable internal fun HydraOrbitTheme(content:@Composable ()->Unit) {
    val scheme=MaterialTheme.colorScheme
    val accents=if(scheme.background.luminance()<.5f)OrbitDark else OrbitLight
    val orbitScheme=if(scheme.primary==scheme.secondary&&scheme.primary==scheme.tertiary)scheme.copy(
        secondary=accents.secondary,secondaryContainer=accents.secondaryContainer,
        tertiary=accents.tertiary,tertiaryContainer=accents.tertiaryContainer) else scheme
    MaterialTheme(colorScheme=orbitScheme,typography=MaterialTheme.typography,shapes=MaterialTheme.shapes,content=content)
}

/** A semantic warning palette, independent of the user's dynamic accent color. */
@Composable internal fun HydraWarningTheme(content:@Composable ()->Unit) {
    val cs=MaterialTheme.colorScheme
    val dark=cs.background.luminance()<.5f
    MaterialTheme(colorScheme=cs.copy(tertiary=if(dark)Color(0xFFFFB77D)else Color(0xFF854500),
        tertiaryContainer=if(dark)Color(0xFF653400)else Color(0xFFFFDCC2),
        onTertiaryContainer=if(dark)Color(0xFFFFDCC2)else Color(0xFF2D1600)),
        typography=MaterialTheme.typography,shapes=MaterialTheme.shapes,content=content)
}

@Composable fun HydraTheme(darkTheme: Boolean? = null, dynamicColor: Boolean? = null, content: @Composable () -> Unit) {
    val context=LocalContext.current
    val appearance=rememberAppearanceState()
    val dark=darkTheme?:when(appearance.themeMode){AppThemeMode.SYSTEM->isSystemInDarkTheme();AppThemeMode.LIGHT->false;AppThemeMode.DARK->true}
    val scheme=if((dynamicColor?:appearance.dynamicColor) && Build.VERSION.SDK_INT>=31) {
        if(dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if(dark) Dark else Light
    val view=LocalView.current
    if(!view.isInEditMode)SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window,view).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
    }
    CompositionLocalProvider(LocalMarqueeEnabled provides appearance.marquee) {
        MaterialTheme(colorScheme = scheme,
            typography = Typography(
                displaySmall = TextStyle(fontFamily = InstrumentSerif, fontSize = 42.sp, lineHeight = 46.sp),
                headlineMedium = TextStyle(fontFamily = InstrumentSerif, fontSize = 32.sp, lineHeight = 38.sp),
                titleLarge = TextStyle(fontFamily = InstrumentSans, fontSize = 22.sp, lineHeight = 28.sp),
                titleMedium = TextStyle(fontFamily = InstrumentSans, fontSize = 16.sp, lineHeight = 24.sp),
                bodyLarge = TextStyle(fontFamily = InstrumentSans, fontSize = 16.sp, lineHeight = 25.sp),
                bodyMedium = TextStyle(fontFamily = InstrumentSans, fontSize = 14.sp, lineHeight = 21.sp),
                labelLarge = TextStyle(fontFamily = InstrumentSans, fontSize = 14.sp, lineHeight = 20.sp),
                labelMedium = TextStyle(fontFamily = InstrumentSans, fontSize = 12.sp, lineHeight = 16.sp),
                labelSmall = TextStyle(fontFamily = InstrumentSans, fontSize = 11.sp, lineHeight = 16.sp)
            ), content = content)
    }
}
