package app.gifitup.ui

import androidx.compose.runtime.Composable

/**
 * Whether the user asked for less motion (`prefers-reduced-motion` on the web, animations turned off on Android): a GIF
 * then shows its first frame and stays still.
 */
@Composable
internal expect fun prefersReducedMotion(): Boolean
