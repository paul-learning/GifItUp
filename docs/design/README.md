# GifItUp design notes

The look of the app, written down so new screens match (#154, #217). **The code is the source of truth**: the colors and
shapes live only in `composeApp/src/commonMain/kotlin/app/gifitup/ui/GifItUpTheme.kt`, and the shared pieces in
`ui/Components.kt` and `ui/Buttons.kt`. This file says what they are for and how to use them; if it and the code
disagree, the code wins and this file gets fixed. The owner's drafts that set the direction are in
[`154/`](154/README.md) (`friends-draft.png`, `conversation-draft.png`).

## Direction
Deep purple surfaces, rounded cards, a purple primary, an **orange accent only for what needs the user's attention**
(a changed key, a review). People are initials in circles. A square rounded back button. Light and dark share the same
roles; the system decides which one shows (`prefers-color-scheme` on the web). No custom fonts: Material 3's type scale.

## Color roles (Material 3 `ColorScheme`)
Screens use **roles** (`MaterialTheme.colorScheme.primary`, `surfaceContainer`, …), never a hex value, so a retheme is
one file.

| Role | Dark | Light | Used for |
|---|---|---|---|
| `primary` / `onPrimary` | `#8B5CF6` / `#0A0516` | `#6E3FD9` / `#FFFFFF` | main buttons, own message bubbles, the round "+" |
| `secondary` | `#B79CFF` | `#5B34B8` | own sender name, quiet emphasis |
| `tertiary` / `onTertiary` | `#F5A55A` / `#2B1600` | `#9A4A00` / `#FFFFFF` | **attention** (orange): a key to review, a warning that needs a decision |
| `background`, `surface` | `#100D1A` | `#FAF7FF` | the screen |
| `surfaceContainer` | `#1C1727` | `#F0EBFA` | cards (`CardSurface`) |
| `surfaceContainerHigh` / `Highest` | `#2A2238` / `#332A45` | `#E4DBF6` / `#D9CEF0` | received bubbles, secondary buttons, nested cards |
| `onSurface` / `onSurfaceVariant` | `#F4F1FA` / `#A9A1BC` | `#1B1626` / `#5E5670` | text / quieter text |
| `outline` | `#6F6785` | `#857C99` | borders |
| `error` / `onError` | `#FF8A80` / `#3B0A06` | `#B3261E` / `#FFFFFF` | errors, destructive confirmations |

Rule: **orange means "look at this"**, not decoration; red (`error`) means something failed or is destructive.
Contrast: text on `primary` and on every container uses the paired `on…` role, never a hand-picked color.

## Shapes
`extraSmall` 8 dp, `small` 12 dp (chips), `medium` 16 dp (buttons, text fields), `large` 24 dp (bubbles, cards),
`extraLarge` 28 dp.

## Spacing and sizes in use
Screen padding 16 to 20 dp horizontally; 8 dp between related items, 12 to 16 dp between groups; cards pad 16 dp.
Buttons are at least 52 dp high and full width; the round "+" is 72 dp; icon tiles (back, info) 48 dp; avatars 40 dp in
lists, 36 dp in the member stack (overlapping by 10 dp, at most 3), 24 dp beside a sender name. Every tap target is at
least 48 dp.

## Shared components
- `AppTopBar(title, onBack, subtitle, trailing)`: every screen's top row; `IconTile` + `Chevron` is the back button.
- `CardSurface`: the rounded card for a group of related content; `ActionCard` a card that is one big action.
- `PrimaryButton` (purple, one per screen), `SecondaryButton` (container color), `PlusButton` (the round "+", dimmed when off).
- `Avatar(name)` initials in a circle; `MemberAvatars(names)` the overlapping stack.
- `GifTileView` shows a loaded GIF or an inert placeholder of the same shape; use it for every GIF, never an `<img>`.
- Icons are drawn in code (`InfoIcon`, `PeopleIcon`, `Chevron`): no icon font, no extra dependency.

## Rules every screen follows
- **Screens render state; they decide nothing** (ADR-0013): logic lives in a state holder.
- Text from another person (a username, a caption, a chat name) goes through `ChatTexts.isolated(...)` so it can't
  reorder the surrounding text; text a provider sends (a GIF's slug or title) is **never shown** or read by a screen reader.
- A change of status text is announced: `Modifier.polite()` (a live region; defined in `ui/ConversationScreen.kt`).
- Insets: the window is edge to edge, and `App` keeps all content out of the system bars, a display cutout and the
  keyboard (`WindowInsets.safeDrawing`), so a screen must not add its own status-bar padding.
- Every action has a content description or label for a screen reader; a disabled control says why in text near it.
- Dark and light must both be checked; reduced motion is respected (`ReducedMotion`).
- Fixed words live in a `…Texts` object next to the screen (`ChatTexts`, `ConversationTexts`, `GifPickerTexts`), not in the
  composable.

## Not designed yet
Typography overrides, motion, tablet/desktop beyond the two-pane chat layout, an app icon (#211), and the screens of #217
(landing list, menu, profile). They are added here when they are built.
