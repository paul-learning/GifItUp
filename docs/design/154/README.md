# Visual design draft (#154)

Early drafts by the owner, 2026-10-28 (a dated snapshot: since 2026-11-02 a caption sits under the GIF in the bubble, see `../README.md`). They set the direction, not pixel values: dark purple surfaces, rounded cards, a
purple primary, an orange accent for "needs your review", initials in circles for people, a square rounded back button.

- `friends-draft.png`: the Friends screen. Own key emojis sit behind the info button on the "Key emojis" card; a friend
  whose key changed has an orange outline and a *Review* button.
- `conversation-draft.png`: a conversation. "GIF" chip on each bubble, caption on the GIF, sender avatar, name and time
  below, own messages on the right in the primary color with a delivery state, member avatars and "end-to-end
  encrypted" in the header, one round "+" button to send a GIF. The reaction chip (😂 2) belongs to #88, not built yet.

## Second direction: slicker (2026-11-11)

The first drafts read too playful (owner, #154). `chat-list-draft.png` sets the new direction for **all** screens, again as
a direction and not pixel values: flat rows with a hairline divider instead of cards, plain text tabs with a short
purple underline instead of chips, icon buttons without a filled tile, smaller and quieter buttons (40 dp high, softer
corners, medium weight), a small unread dot instead of a count badge, a smaller round "+" (56 dp), tighter corner radii
and lighter font weights. Built in the shared theme and components (`GifItUpTheme.kt`, `Buttons.kt`, `Components.kt`),
so every screen follows. Not done yet: the conversation bubbles (`MessageRow.kt`) and the header wording (#283).
