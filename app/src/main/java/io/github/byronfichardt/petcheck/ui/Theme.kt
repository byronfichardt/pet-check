package io.github.byronfichardt.petcheck.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.byronfichardt.petcheck.data.SoundType

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B64BE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E8FC),
    onPrimaryContainer = Color(0xFF0B2F5E),
    secondary = Color(0xFF4F5F78),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E8F1),
    onSecondaryContainer = Color(0xFF1C2637),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEBC2),
    onTertiaryContainer = Color(0xFF4A3000),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFBE4E2),
    onErrorContainer = Color(0xFF5F1410),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF191B1F),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF191B1F),
    surfaceVariant = Color(0xFFE6E9EE),
    onSurfaceVariant = Color(0xFF5A6069),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEFF1F5),
    surfaceContainerHigh = Color(0xFFE9ECF0),
    surfaceContainerHighest = Color(0xFFE3E6EB),
    outline = Color(0xFF8A9099),
    outlineVariant = Color(0xFFDADEE4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF84B9F3),
    onPrimary = Color(0xFF07305F),
    primaryContainer = Color(0xFF12447F),
    onPrimaryContainer = Color(0xFFD9E8FC),
    secondary = Color(0xFFB6C3D8),
    onSecondary = Color(0xFF202C3F),
    secondaryContainer = Color(0xFF2C3646),
    onSecondaryContainer = Color(0xFFDCE4F1),
    tertiary = Color(0xFFF2C56B),
    onTertiary = Color(0xFF412B00),
    tertiaryContainer = Color(0xFF5A3F06),
    onTertiaryContainer = Color(0xFFFFE6B3),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF5C1D19),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF121315),
    onBackground = Color(0xFFE6E7EA),
    surface = Color(0xFF121315),
    onSurface = Color(0xFFE6E7EA),
    surfaceVariant = Color(0xFF2A2D32),
    onSurfaceVariant = Color(0xFFA9AFB8),
    surfaceContainerLowest = Color(0xFF0D0E10),
    surfaceContainerLow = Color(0xFF1B1D20),
    surfaceContainer = Color(0xFF1F2125),
    surfaceContainerHigh = Color(0xFF26292D),
    surfaceContainerHighest = Color(0xFF303338),
    outline = Color(0xFF7D838C),
    outlineVariant = Color(0xFF34373C),
)

/**
 * One colour per sound type, the same on every screen. The hues and their stacking order
 * ([STACK_ORDER]) were checked for colour-blind separation between neighbours in both themes.
 * "Other" is a deliberate neutral. Change them only as a set.
 */
@Immutable
class SoundColors(private val colors: Map<SoundType, Color>) {
    operator fun get(type: SoundType): Color = colors.getValue(type)

    companion object {
        /** Bottom to top in a stacked bar. */
        val STACK_ORDER = listOf(
            SoundType.OTHER,
            SoundType.BARK,
            SoundType.HOWL,
            SoundType.WHINE,
            SoundType.GROWL,
            SoundType.MEOW,
        )

        val Light = SoundColors(
            mapOf(
                SoundType.OTHER to Color(0xFF8A8A8A),
                SoundType.BARK to Color(0xFF257DE0),
                SoundType.HOWL to Color(0xFFF47230),
                SoundType.WHINE to Color(0xFF00B894),
                SoundType.GROWL to Color(0xFFF2A600),
                SoundType.MEOW to Color(0xFFEE7FAC),
            ),
        )

        val Dark = SoundColors(
            mapOf(
                SoundType.OTHER to Color(0xFF7A7A7A),
                SoundType.BARK to Color(0xFF338CED),
                SoundType.HOWL to Color(0xFFE76624),
                SoundType.WHINE to Color(0xFF00A888),
                SoundType.GROWL to Color(0xFFD28E05),
                SoundType.MEOW to Color(0xFFE24D8B),
            ),
        )
    }
}

val LocalSoundColors = staticCompositionLocalOf { SoundColors.Light }

val SoundType.label: String
    get() = when (this) {
        SoundType.BARK -> "Bark"
        SoundType.HOWL -> "Howl"
        SoundType.WHINE -> "Whine"
        SoundType.GROWL -> "Growl"
        SoundType.MEOW -> "Meow"
        SoundType.OTHER -> "Other"
    }

@Composable
fun PetCheckTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSoundColors provides if (darkTheme) SoundColors.Dark else SoundColors.Light) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
    }
}
