package bg.solar.nightcharge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Черно и бяло от стила на логото (черен кръг, тънки бели линии). */
val BrandBlack = Color(0xFF111111)
val DeyeBlue = Color(0xFF0060C0)
val DeyeBlueDark = Color(0xFF00447F)
val DeyeRed = Color(0xFFE02020)
val DeyeBackground = Color(0xFFF2F5F9)
val DeyeText = Color(0xFF1B2430)
val DeyeMuted = Color(0xFF5A6675)
val DeyeGreen = Color(0xFF2BA84A)
val DeyeOrange = Color(0xFFF39C12)

/** Цветовете на сериите в графиките, както в Deye Cloud. */
val SeriesProduction = Color(0xFF5BC45B)
val SeriesConsumption = Color(0xFFF5B800)
val SeriesPurchased = Color(0xFF8E6CE0)
val SeriesCharge = Color(0xFF2C4BB0)
val SeriesDischarge = Color(0xFF7CC4F4)
val SeriesFeedIn = Color(0xFFF08B7E)

private val lightScheme = lightColorScheme(
    primary = DeyeBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6F8),
    onPrimaryContainer = DeyeBlueDark,
    secondary = DeyeBlueDark,
    onSecondary = Color.White,
    background = DeyeBackground,
    onBackground = DeyeText,
    surface = Color.White,
    onSurface = DeyeText,
    surfaceVariant = Color(0xFFE8EEF5),
    onSurfaceVariant = DeyeMuted,
    error = DeyeRed,
    onError = Color.White,
)

private val darkScheme = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    onPrimary = Color(0xFF002A55),
    primaryContainer = Color(0xFF00447F),
    onPrimaryContainer = Color(0xFFD6E6F8),
    secondary = Color(0xFF9CC7FF),
    onSecondary = Color(0xFF00213F),
    background = Color(0xFF0E141B),
    onBackground = Color(0xFFE6ECF2),
    surface = Color(0xFF17202A),
    onSurface = Color(0xFFE6ECF2),
    surfaceVariant = Color(0xFF233041),
    onSurfaceVariant = Color(0xFFA9B6C4),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0000),
)

/** theme: "light" и "dark" са избор на потребителя, "auto" следва телефона. */
@Composable
fun SolarTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, content = content)
}
