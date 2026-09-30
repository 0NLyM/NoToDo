package app.notodo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notodo.parse.Kind

private val Light = lightColorScheme(
    primary = Color(0xFFC8102E), onPrimary = Color.White,
    secondary = Color(0xFF111111), onSecondary = Color.White,
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
    secondary = Color(0xFFF2F2F2), onSecondary = Color.Black,
    background = Color(0xFF0A0A0A), onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF141414), onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1E1E1E), onSurfaceVariant = Color(0xFFABABAB),
    surfaceContainerLowest = Color(0xFF0A0A0A), surfaceContainerLow = Color(0xFF121212), surfaceContainer = Color(0xFF161616),
    surfaceContainerHigh = Color(0xFF1C1C1C), surfaceContainerHighest = Color(0xFF232323),
    outline = Color(0xFF3A3A3A), outlineVariant = Color(0xFF262626),
    error = Color(0xFFFF5A5F), onError = Color.Black,
)

val Mono = FontFamily.Monospace

@Composable
fun NoToDoTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = base.copy(
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
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
fun KindGlyph(kind: Kind, modifier: Modifier = Modifier) = Box(
    modifier.size(28.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        .semantics { contentDescription = kind.label },
    contentAlignment = Alignment.Center,
) { Text(kind.glyph, fontFamily = Mono, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
