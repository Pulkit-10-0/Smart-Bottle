// Theme.kt
package com.example.smartbottle.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.isSystemInDarkTheme

private val LightColors = lightColorScheme(
    primary            = GreenPrimary,
    onPrimary          = OnLightBackground,
    background         = LightBackground,
    onBackground       = OnLightBackground,
    surface            = LightSurface,
    onSurface          = OnLightBackground,
    secondary          = GreenAccent,
    onSecondary        = OnLightBackground
)

private val DarkColors = darkColorScheme(
    primary            = GreenPrimary,
    onPrimary          = OnDarkBackground,
    background         = DarkBackground,
    onBackground       = OnDarkBackground,
    surface            = DarkSurface,
    onSurface          = OnDarkBackground,
    secondary          = GreenAccent,
    onSecondary        = OnDarkBackground
)

@Composable
fun SmartBottleTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors: ColorScheme = if (useDarkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography  = MaterialTheme.typography,
        content     = content
    )
}
