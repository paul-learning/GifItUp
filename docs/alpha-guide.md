# GifItUp alpha guide

*Serious crypto for silly people.*

GifItUp is a private messenger for a small circle of friends where the only thing you can send is a GIF (with an optional
one-line caption, and a reaction emoji on a GIF). Every chat is end-to-end encrypted. This is an **alpha**: it works, it has
rough edges, and this page tells you honestly what is private, what is not, and what is missing.

## Getting in

You need an **invite** from the person who runs this GifItUp. It is a single-use link that expires. Nobody can register
without one.

- **Android (Android 9 or newer):**
  1. Open the releases page of the project, `https://github.com/paul-learning/GifItUp/releases`, and download the APK of the
     newest release (`gifitup-vX.Y.Z.apk`). Releases are published by the owner and cannot be changed afterwards.
  2. Open the file. Android asks you to allow installs from your browser or file manager: allow it for this one install.
  3. Open your invite link. If the app is installed, the link opens the app; otherwise it opens in the browser, and you can
     also paste the link or code into the app's "Invite link or code" field.
- **Web:** open the address the owner gave you in a current, up-to-date browser and open your invite link there. Keep it
  in **one tab**: a second tab says the app is open elsewhere.

**Careful? Check the APK.** The owner sends you the signing certificate's fingerprint in a separate message (not in
GifItUp). On a computer, `apksigner verify --print-certs gifitup-vX.Y.Z.apk` prints the certificate's SHA-256, which has to be
the same. The release also lists the APK's own checksum in `SHA256SUMS`. If either differs, do not install it and tell the
owner.

## Using it

- **Chats:** start a chat from the list. A chat with one friend is a direct chat; give it a name and add more friends and it
  is a group.
- **Send a GIF:** tap the button at the bottom, search, pick a GIF, optionally add a caption (120 characters), send.
- **React:** under a GIF, tap **React** (or one of the small reaction chips). The window shows who reacted with what, a row of
  common emojis and the whole list. Tap your own emoji again, or "Remove my reaction", to take it back. One reaction per
  person per GIF; everyone in the chat sees who reacted. On the web, the first load downloads a font of about 2.5 MB so the
  emojis can be drawn.
- **Friends:** the Friends screen lists the people you can talk to.

## Check that your friend is really your friend

When you add someone, the app shows **12 emojis with their names**. These come from your friend's key. The only way to be sure
that nobody sits in the middle is to compare them **with your friend, on a call or in person, not in a chat**: you read yours
out, they read theirs out, and **all 12 names must match**. Don't skip this for people you care about. The app never says
"verified"; it only says that you compared.

If a friend's key changes (new phone, reinstall, or something worse), the app blocks that chat and asks you to compare again.

## What is private, and what is not

**Private:**
- **What you send.** Messages and GIF choices are encrypted on your device and can be read only by the members of the chat.
  The server stores and passes on sealed data. It does not learn which GIF you sent or what you searched for.

**Not private, so you know:**
- **The GIF provider** (Klipy, behind Cloudflare) sees your search words and your address when you search, and every person's
  address and the GIF when they open it. The GifItUp server does not see this. The provider could link a search to the friends
  who then open the same GIF.
- **The person running the server** can see who is registered, who is in which chat, when messages are sent and how often,
  roughly how big they are (they are padded), and the addresses people connect from. They cannot read the content.
- **The server can stop or delay messages.** Encryption does not prevent a server from holding messages back.
- **On the web, the app's code comes from the server** every time you open it. A dishonest server could serve a changed app
  that reads everything. So the key comparison above is only as trustworthy as that server's honesty on the web. **The Android
  app is the stronger choice**, and where it matters, use it.
- **On your device:** Android keeps its secrets in the phone's protected storage. The web app has no password or passphrase:
  anyone who can read your browser profile (a stolen laptop, malware, a shared computer) can read your chats. Don't use the web
  app on a computer that is not yours.
- **Anyone you chat with** can keep or share what you send them, like any messenger.

## Known limitations (alpha)

- **No account recovery.** If you lose your phone or clear the app's data, your account and your history on it are gone. Ask the
  owner for a new invite.
- Only Android 9+ and the web. No iPhone app.
- No push notifications yet: open the app to see new messages. The list refreshes while it is open.
- History is kept on the device only: the latest 1000 messages per chat on Android, 200 on the web.
- The server limits how fast you can start new chats and send, to stop abuse. If it asks you to slow down, the app says so.
- A friend on an older phone may not be able to draw every reaction emoji; the app only offers emojis your device can draw.

## Problems

Tell the owner. If you think you found a security problem, say so privately (see `SECURITY.md`), not in a group chat.
