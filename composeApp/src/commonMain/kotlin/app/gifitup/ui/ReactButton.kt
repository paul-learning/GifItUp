package app.gifitup.ui

/**
 * Whether a message shows a visible "React" button next to its reactions. The web does; Android reacts only by a long tap on
 * the GIF (owner, #154), a tap on an existing reaction chip opens the reactions on both.
 */
internal expect val SHOW_REACT_BUTTON: Boolean
