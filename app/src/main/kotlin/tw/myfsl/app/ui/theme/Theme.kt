package tw.myfsl.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

/** 狀態色（花太快、超支）不隨動態色改變。 */
object StatusColors {
    val warningFill = Color(0xFFC69200)
    val warningContainer = Color(0xFFFFDF9E)
    val onWarningContainer = Color(0xFF261A00)
    val warningText = Color(0xFF7A5900)
}

/** 支付方式的標示色，沿用常見預算表的底色習慣：信用卡淺藍、現金黃、轉帳灰綠。 */
object MethodColors {
    val card = Color(0xFF7FB3E0)
    val cash = Color(0xFFE8C547)
    val transfer = Color(0xFF8FB5A8)
}

@Composable
fun MyFslTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
