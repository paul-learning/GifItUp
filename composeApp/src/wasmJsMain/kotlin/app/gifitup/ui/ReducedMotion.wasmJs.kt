package app.gifitup.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun prefersReducedMotion(): Boolean = reducedMotionQuery()

@JsFun("() => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches")
private external fun reducedMotionQuery(): Boolean
