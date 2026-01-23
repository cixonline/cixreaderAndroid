package com.cixonline.cixreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun CIXReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontSizeMultiplier: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Identify which styles should be scaled. 
    // Usually, users want "Content" (Body, Headline, Display) to scale,
    // while "Chrome" (Title for TopBars, Label for Buttons) stays fixed for UI consistency.
    val scaledTypography = Typography(
        displayLarge = Typography.displayLarge.scale(fontSizeMultiplier),
        displayMedium = Typography.displayMedium.scale(fontSizeMultiplier),
        displaySmall = Typography.displaySmall.scale(fontSizeMultiplier),
        headlineLarge = Typography.headlineLarge.scale(fontSizeMultiplier),
        headlineMedium = Typography.headlineMedium.scale(fontSizeMultiplier),
        headlineSmall = Typography.headlineSmall.scale(fontSizeMultiplier),
        
        // Fixed sizes for TopBars and navigation titles
        titleLarge = Typography.titleLarge,
        titleMedium = Typography.titleMedium,
        titleSmall = Typography.titleSmall,
        
        // Scaled body text for main content
        bodyLarge = Typography.bodyLarge.scale(fontSizeMultiplier),
        bodyMedium = Typography.bodyMedium.scale(fontSizeMultiplier),
        bodySmall = Typography.bodySmall.scale(fontSizeMultiplier),
        
        // Fixed sizes for Buttons and UI labels
        labelLarge = Typography.labelLarge,
        labelMedium = Typography.labelMedium,
        labelSmall = Typography.labelSmall
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = scaledTypography,
        content = {
            SelectionContainer {
                content()
            }
        }
    )
}

private fun TextStyle.scale(multiplier: Float): TextStyle {
    return this.copy(fontSize = this.fontSize * multiplier)
}
