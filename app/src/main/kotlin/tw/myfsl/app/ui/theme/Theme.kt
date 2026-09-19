package tw.myfsl.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

// 色彩與設計稿（design/mockups）一致：Material 3，主色青綠。
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B61),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA6F2E4),
    onPrimaryContainer = Color(0xFF00201C),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF05201C),
    background = Color(0xFFF5FAF8),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF5FAF8),
    surfaceVariant = Color(0xFFDBE5E1),
    surfaceBright = Color(0xFFF5FAF8),
    surfaceDim = Color(0xFFD5DBD9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F2),
    surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3EAE7),
    surfaceContainerHighest = Color(0xFFDDE4E1),
    onSurface = Color(0xFF171D1B),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
    error = Color(0xFFBA1A1A),
    inverseSurface = Color(0xFF2C3230),
    inverseOnSurface = Color(0xFFECF2EF),
    inversePrimary = Color(0xFF8BD6C9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD6C9),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFA6F2E4),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0E1513),
    surfaceVariant = Color(0xFF3F4946),
    surfaceBright = Color(0xFF343A38),
    surfaceDim = Color(0xFF0E1513),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303634),
    onSurface = Color(0xFFDDE4E1),
    onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
    error = Color(0xFFFFB4AB),
    inverseSurface = Color(0xFFDDE4E1),
    inverseOnSurface = Color(0xFF2C3230),
    inversePrimary = Color(0xFF1B6B61),
)

/**
 * 警示色角色（快到期、會超出、少於最低應繳）。M3 沒有內建「警示」，照 material-3 skill 的配對規則補一組：
 * [warning] 放在 surface 上的字與圖示；[warningContainer] 是底色，上面的字用 [onWarningContainer]。淺色、深色各一套。
 */
@Immutable
data class WarningColors(
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightWarning = WarningColors(
    warning = Color(0xFF7A5900),
    warningContainer = Color(0xFFFFDF9E),
    onWarningContainer = Color(0xFF261A00),
)

private val DarkWarning = WarningColors(
    warning = Color(0xFFF0C048),
    warningContainer = Color(0xFF5C4300),
    onWarningContainer = Color(0xFFFFDF9E),
)

private val LocalWarningColors = staticCompositionLocalOf { LightWarning }

/** 用法：`MaterialTheme.warningColors.warning`。 */
val MaterialTheme.warningColors: WarningColors
    @Composable @ReadOnlyComposable get() = LocalWarningColors.current

/**
 * 圖表的線色（dataviz skill 的參考配色，依固定順序配給情境，隱藏某條線也不換色）。
 * 淺色與深色各一套，已用 validate_palette 檢查色盲區分度；淺色版有三色對背景低於 3:1，
 * 所以圖表一定附圖例與比較表（skill 規定的補救）。
 */
@Immutable
data class ChartColors(val series: List<Color>) {
    fun at(index: Int): Color = series[index % series.size]
}

private val LightChart = ChartColors(listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFFE87BA4)))
private val DarkChart = ChartColors(listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500), Color(0xFFD55181)))

private val LocalChartColors = staticCompositionLocalOf { LightChart }

val MaterialTheme.chartColors: ChartColors
    @Composable @ReadOnlyComposable get() = LocalChartColors.current

/** 間距（material-3 skill：4dp 格線）。畫面只用這些值。 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/**
 * M3 動態的時間與曲線（material-3 skill 的 Duration／Easing 表）。
 * 目前的 Material3 版本還沒有 MotionScheme，MotionTokens 又不公開，所以照規格值定義在這裡。
 */
object Motion {
    const val SHORT4 = 200
    const val MEDIUM2 = 300
    const val FEEDBACK_HOLD_MS = 1_000L
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

@Composable
fun MyFslTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalWarningColors provides if (darkTheme) DarkWarning else LightWarning,
        LocalChartColors provides if (darkTheme) DarkChart else LightChart,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
