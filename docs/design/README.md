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
- `AppTopBar(title, onBack, modifier, subtitle, trailing, backLabel, backEnabled)`: every screen's top row (`onBack = null` on the
  landing screen); `IconTile` + `Chevron` is the back button, `IconTile` + `MenuIcon` the burger menu.
- `CardSurface`: the rounded card for a group of related content; `ActionCard` a card that is one big action.
- `PrimaryButton` (purple, one per screen), `SecondaryButton` (container color), `PlusButton` (the round "+", dimmed when off).
- `Avatar(name)` initials in a circle; `MemberAvatars(names)` the overlapping stack.
- `GifTileView` shows a loaded GIF or an inert placeholder of the same shape; use it for every GIF, never an `<img>`.
- `OwnEmojisCard` and `OwnEmojisDialog` show the user's own key emojis (Friends and Profile screens).
- Icons are drawn in code (`InfoIcon`, `PeopleIcon`, `Chevron`, `MenuIcon`): no icon font, no extra dependency.

## Screens (#217)
- **Landing:** after sign-in the first screen is the chat list: title "Chats" with a burger menu on the right (Profile,
  Invite a friend, Friends), filter chips under it (All, Direct, Groups; direct = one other member and no name, a group has a name or
  two or more others, see ADR-0006 B3), the round "+" at the lower right for a new chat or group. There is no back button: it is the start.
- **New chat:** two modes as chips: "Chat with a friend" (one friend, no name: a direct chat) and "New group" (a name of 1 to 64 characters and one or more friends; the name is sent after the friends are in). Only a group can grow. A friend you already have a direct chat with is marked "You already have a chat with them", and "Open chat" opens that chat instead of making a second one (a frozen, removed or unreadable chat doesn't count; a group can still include them).
- **Contact profile:** the same friend card as the Friends screen (the saved key as emojis to compare, a review when it changed) and "Known since <date>" (the UTC day this device first saw them: the server sends no time). Reached by a long tap on a **direct** chat in the list, or the info button in the header of a direct chat.
- **Profile:** the server (the banner, orange while it is custom), the username and the own key emojis (the same card and dialog as on the Friends screen).
- **Member standing (#31):** in the group info a member who is not a saved friend has a note under the name: "Not in your friends", or "Not the <name> you saved: another key" when the name is a friend's but the chat's key is not the saved one (decided by key against the pin, never by name). A frozen chat says why in neutral words ("this chat's member data does not match", "a change would give a friend another key"), naming nobody; a commit this device made itself shows as "You added <x>".
- **Group info:** reached by the info button in the header of a **group** conversation: the name, the members and (while the chat is a group this device can grow) the friends who are not in it, to add several at once. Each friend is added with its own commit and the name is sent again afterwards, so the new members see it.
- **Message bubble:** the GIF with the small "GIF" chip on it, and its caption **under** the GIF inside the same bubble, as plain text on the bubble's colour (never over the picture), wrapping up to 6 lines and ellipsised after that.
- **Friends, key of a chat (#31):** a friend who is only listed but shares a chat with you shows "The key your chat with <name> uses. Not saved yet." with the 12 emojis; "Save this key" (after comparing with the friend, who reads their own phone's "Your emojis") saves it. A name with more than one key or phone, an unverified phone, or a list that shows another key gets a note and no button.
- **Membership notices (#31):** a small centred line in the conversation for a change of the members: "<x> added <y>", "<y> was removed by <x>", "You joined this chat with N others, added by <x>". A notice is not a message: no sender line, never unread, never the line of the chat list. At most 30 per chat are kept.
- **Pausing:** a tap on a playing GIF (in a bubble, and on the send step) pauses it with a "Paused" label on the held frame; another tap plays it again. In the picker a tap chooses the GIF, so nothing pauses there. The state is not kept: a GIF that leaves the screen and returns plays again.
- **Send step:** picking a GIF in the picker opens it large with an optional caption field and "Send" under it (the GIF's height is capped so the caption and Send stay on screen); Back returns to the results and drops the typed caption.
- A notice above the list: a note while some setup is pending. While the server is not the default, the top bar carries a small orange ring-and-exclamation marker ("Custom server: tap to see which") that opens the Profile, which shows the server in full (ADR-0013); the sign-up steps keep the full banner.

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
Typography overrides, motion, tablet/desktop beyond the two-pane chat layout, an app icon (#211), and the group info screen
(#217). They are added here when they are built.
