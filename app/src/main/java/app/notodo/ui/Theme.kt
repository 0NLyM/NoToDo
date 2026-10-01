package app.notodo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notodo.R
import app.notodo.parse.Kind

private val Light = lightColorScheme(
    primary = Color(0xFFC8102E), onPrimary = Color.White,
    primaryContainer = Color(0xFFF8DADF), onPrimaryContainer = Color(0xFF4A000D),
    secondary = Color(0xFF111111), onSecondary = Color.White,
    secondaryContainer = Color(0xFF111111), onSecondaryContainer = Color.White,
    tertiary = Color(0xFF111111), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6E6E3), onTertiaryContainer = Color(0xFF111111),
    surfaceTint = Color.Transparent,
    background = Color(0xFFF5F5F3), onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEDEDEA), onSurfaceVariant = Color(0xFF555555),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAF8), surfaceContainer = Color(0xFFF7F7F5),
    surfaceContainerHigh = Color(0xFFF1F1EF), surfaceContainerHighest = Color(0xFFEBEBE8),
    outline = Color(0xFFCFCFCB), outlineVariant = Color(0xFFE2E2DE),
    error = Color(0xFFC8102E), onError = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF5A5F), onPrimary = Color.Black,
    primaryContainer = Color(0xFF4A1015), onPrimaryContainer = Color(0xFFFFDADC),
    secondary = Color(0xFFF2F2F2), onSecondary = Color.Black,
    secondaryContainer = Color(0xFFF2F2F2), onSecondaryContainer = Color.Black,
    tertiary = Color(0xFFF2F2F2), onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF2A2A2A), onTertiaryContainer = Color(0xFFF2F2F2),
    surfaceTint = Color.Transparent,
    background = Color(0xFF0A0A0A), onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF141414), onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1E1E1E), onSurfaceVariant = Color(0xFFABABAB),
    surfaceContainerLowest = Color(0xFF0A0A0A), surfaceContainerLow = Color(0xFF121212), surfaceContainer = Color(0xFF161616),
    surfaceContainerHigh = Color(0xFF1C1C1C), surfaceContainerHighest = Color(0xFF232323),
    outline = Color(0xFF3A3A3A), outlineVariant = Color(0xFF262626),
    error = Color(0xFFFF5A5F), onError = Color.Black,
)

val Mono = FontFamily.Monospace

/** Testo: Geist (OFL), variabile. */
val Geist = FontFamily(
    listOf(400, 500, 600, 700).map { w -> Font(R.font.geist, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) },
)

/** Titoli: Doto (OFL), matrice di punti con angoli arrotondati (asse ROND al massimo: punti rotondi). */
val Dots = FontFamily(
    Font(
        R.font.doto,
        FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800), FontVariation.Setting("ROND", 100f)),
    ),
)

private val typography = Typography().run {
    fun TextStyle.body() = copy(fontFamily = Geist)
    fun TextStyle.title() = copy(fontFamily = Dots, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp)
    Typography(
        displayLarge = displayLarge.title(), displayMedium = displayMedium.title(), displaySmall = displaySmall.title(),
        headlineLarge = headlineLarge.title(), headlineMedium = headlineMedium.title(), headlineSmall = headlineSmall.title(),
        titleLarge = titleLarge.title(),
        titleMedium = titleMedium.body().copy(fontWeight = FontWeight.SemiBold), titleSmall = titleSmall.body(),
        bodyLarge = bodyLarge.body(), bodyMedium = bodyMedium.body(), bodySmall = bodySmall.body(),
        labelLarge = labelLarge.body(), labelMedium = labelMedium.body(), labelSmall = labelSmall.body(),
    )
}

@Composable
fun NoToDoTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) Dark else Light
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
    ) {
        // Fuori da una Surface il colore del contenuto di Material è nero: sul tema scuro testo e icone sparirebbero.
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

/** Etichetta tecnica: maiuscolo monospace spaziato. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text.uppercase(), modifier, color = color, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.4.sp)

@Composable
fun Dot(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, size: Dp = 7.dp) =
    Box(modifier.size(size).clip(CircleShape).background(color))

val Kind.glyph get() = when (this) {
    Kind.TASK -> "T"; Kind.EVENT -> "A"; Kind.NOTE -> "N"; Kind.IDEA -> "I"; Kind.REFERENCE -> "R"; Kind.VERIFY -> "?"
}

@Composable
fun KindGlyph(kind: Kind, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) = Box(
    modifier.size(28.dp).clip(CircleShape).border(1.dp, color.copy(alpha = .4f), CircleShape)
        .semantics { contentDescription = kind.label },
    contentAlignment = Alignment.Center,
) { Text(kind.glyph, fontFamily = Mono, fontSize = 13.sp, color = color) }

/** Casella rotonda: anello sottile da vuota, disco pieno con tick fine da spuntata. [hole] è lo sfondo su cui sta, per il tick. */
@Composable
fun RoundCheck(checked: Boolean, onChange: (Boolean) -> Unit, color: Color, hole: Color, modifier: Modifier = Modifier) = Box(
    modifier.size(48.dp).clip(CircleShape).toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
        .semantics { contentDescription = "Fatto" },
    contentAlignment = Alignment.Center,
) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val ring = 1.5.dp.toPx()
        if (checked) {
            drawCircle(color)
            drawPath(
                Path().apply { moveTo(.30f * w, .52f * w); lineTo(.44f * w, .66f * w); lineTo(.71f * w, .35f * w) },
                hole, style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        } else drawCircle(color, radius = (w - ring) / 2, style = Stroke(ring))
    }
}
