package app.gifitup.ui

import androidx.compose.runtime.Composable

/**
 * Whether the user asked for less motion (`prefers-reduced-motion` on the web, animations turned off on Android): a GIF
 * then shows its first frame and stays still. It is read when the picture is composed: a setting changed while a GIF
 * is on screen applies from the next time it is shown.
 */
@Composable
internal expect fun prefersReducedMotion(): Boolean
