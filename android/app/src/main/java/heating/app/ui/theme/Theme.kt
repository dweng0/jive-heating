package heating.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import heating.app.R

/** Colours Material 3's scheme has no slot for: heat, off and status states. */
@Immutable
data class HeatingColors(
    val heat: Color,          // heating fill: tiles, dial arc, primary buttons
    val onHeat: Color,
    val heatText: Color,      // heat-coloured text on light surfaces
    val heatContainer: Color, // soft heat background: chips, selected states
    val off: Color,           // off-tile background
    val onOff: Color,
    val track: Color,         // dial track, dividers
    val segment: Color,       // segmented-control trough
    val cool: Color,          // cool schedule blocks
    val onCool: Color,
    val warm: Color,          // mid schedule blocks
    val danger: Color,
    val onDanger: Color,
    val ok: Color,
    val onOk: Color,
)

private val LightHeating = HeatingColors(
    heat = Color(0xFFF28C28), onHeat = Color(0xFF2A1606),
    heatText = Color(0xFF9A4307), heatContainer = Color(0xFFFDE7D2),
    off = Color(0xFFE9E6E1), onOff = Color(0xFF4A443D),
    track = Color(0xFFECE5DC), segment = Color(0xFFEFE9E1),
    cool = Color(0xFFD9E3EA), onCool = Color(0xFF23435A), warm = Color(0xFFF7B977),
    danger = Color(0xFFFBE3E0), onDanger = Color(0xFF8E2418),
    ok = Color(0xFFE3F0E6), onOk = Color(0xFF1E5631),
)

private val DarkHeating = HeatingColors(
    heat = Color(0xFFF28C28), onHeat = Color(0xFF2A1606),
    heatText = Color(0xFFFFB877), heatContainer = Color(0xFF4A2A10),
    off = Color(0xFF2E2924), onOff = Color(0xFFCFC5BA),
    track = Color(0xFF3A332C), segment = Color(0xFF2B2520),
    cool = Color(0xFF2C3B47), onCool = Color(0xFFCBDDEA), warm = Color(0xFF8A5A2B),
    danger = Color(0xFF4A1E19), onDanger = Color(0xFFFFB4A9),
    ok = Color(0xFF1F3526), onOk = Color(0xFFB5DCC0),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFF28C28), onPrimary = Color(0xFF2A1606),
    primaryContainer = Color(0xFFFDE7D2), onPrimaryContainer = Color(0xFF9A4307),
    secondary = Color(0xFF6A6158), onSecondary = Color.White,
    background = Color(0xFFFAF7F2), onBackground = Color(0xFF201A14),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF201A14),
    surfaceVariant = Color(0xFFEFE9E1), onSurfaceVariant = Color(0xFF6A6158),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFAF7F2),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    outline = Color(0xFFD8D0C6), outlineVariant = Color(0xFFECE5DC),
    error = Color(0xFF8E2418), errorContainer = Color(0xFFFBE3E0),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF28C28), onPrimary = Color(0xFF2A1606),
    primaryContainer = Color(0xFF4A2A10), onPrimaryContainer = Color(0xFFFFB877),
    secondary = Color(0xFFB5AAA0), onSecondary = Color(0xFF201A14),
    background = Color(0xFF17130F), onBackground = Color(0xFFF3ECE4),
    surface = Color(0xFF221D18), onSurface = Color(0xFFF3ECE4),
    surfaceVariant = Color(0xFF2B2520), onSurfaceVariant = Color(0xFFB5AAA0),
    surfaceContainer = Color(0xFF221D18), surfaceContainerLow = Color(0xFF17130F),
    surfaceContainerHigh = Color(0xFF2B2520),
    outline = Color(0xFF5A5148), outlineVariant = Color(0xFF3A332C),
    error = Color(0xFFFFB4A9), errorContainer = Color(0xFF4A1E19),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun jakarta(weight: Int) = Font(
    R.font.plus_jakarta_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Jakarta = FontFamily(jakarta(400), jakarta(500), jakarta(600), jakarta(700), jakarta(800))

private val HeatingTypography = Typography().let { base ->
    fun TextStyle.j(weight: Int = 600) = copy(fontFamily = Jakarta, fontWeight = FontWeight(weight))
    Typography(
        displayLarge = base.displayLarge.j(800).copy(fontSize = 76.sp, letterSpacing = (-3).sp),
        displayMedium = base.displayMedium.j(800).copy(fontSize = 40.sp, letterSpacing = (-1.5).sp),
        displaySmall = base.displaySmall.j(800),
        headlineLarge = base.headlineLarge.j(800).copy(fontSize = 30.sp, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.j(800),
        headlineSmall = base.headlineSmall.j(800).copy(fontSize = 22.sp),
        titleLarge = base.titleLarge.j(800).copy(fontSize = 20.sp),
        titleMedium = base.titleMedium.j(700).copy(fontSize = 16.sp),
        titleSmall = base.titleSmall.j(700),
        bodyLarge = base.bodyLarge.j(500),
        bodyMedium = base.bodyMedium.j(500),
        bodySmall = base.bodySmall.j(500),
        labelLarge = base.labelLarge.j(700),
        labelMedium = base.labelMedium.j(700),
        labelSmall = base.labelSmall.j(700).copy(letterSpacing = 0.8.sp),
    )
}

private val HeatingShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val LocalHeatingColors = staticCompositionLocalOf { LightHeating }

@Composable
fun HeatingTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHeatingColors provides if (dark) DarkHeating else LightHeating) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = HeatingTypography,
            shapes = HeatingShapes,
            content = content,
        )
    }
}

/** Shorthand: `HeatingTheme.colors.heat`. */
object HeatingTheme {
    val colors: HeatingColors
        @Composable get() = LocalHeatingColors.current
}
