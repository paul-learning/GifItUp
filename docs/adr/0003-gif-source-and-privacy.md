# ADR-0003: GIF source and GIF privacy

- **Status:** accepted
- **Date:** 2026-10-13
- **Revised:** 2026-10-14 by the GIF-provider spike (#5, [docs/spikes/gif-provider.md](../spikes/gif-provider.md)): provider = Klipy, delivery = Mode B′ (forward the returned URL, validate strictly). Mode A is rejected for provider GIFs.

## Context
- The service must be **free**. It is a hobby project with no paid APIs.
- **The backend must not learn which GIFs were searched for or sent.**
- Free GIF APIs exist, mainly **Giphy** (free beta key; production access is paid) and **Klipy** (free, including production). Tenor's API shut down on 2026-06-30. All of them are third parties that see requests from the client.
- Both Giphy and Klipy forbid storing, re-hosting or proxying their media without written approval, and require their returned media URLs to be used unmodified (spike #5).

## Decision
1. **Provider: Klipy.** **Search happens client → provider directly**, with the provider API key in the client. Search queries never touch our backend. The provider is hidden behind a `GifProvider` interface so Giphy and Klipy can be swapped.
2. **What our backend sees:** only MLS ciphertext and, for own uploads (M3), opaque encrypted blobs. It never sees a GIF ID, URL, or search term.
3. **Delivery mode for provider GIFs: Mode B′** (chosen by spike #5):
   - **Mode B′, direct reference with the returned URL.** The MLS message carries `{provider, slug, url, width, height}`, where `url` is the media URL **exactly as the provider API returned it** (the provider's terms forbid reconstructing it). Every client validates the URL before fetching anything: `https` only; host **exactly** one of the provider's media hosts (`static.klipy.com`, `static1.klipy.com`, `static2.klipy.com`); no user info, port, query or fragment; length ≤ 256; and a path pattern for the provider's URL layout (see the spike). A URL that fails validation shows a placeholder and is never fetched. Fetches send no cookies or `Referer`, don't follow redirects off the allowlist, and are size-capped. The provider sees recipients' IPs and the GIF; our backend still sees nothing. The host allowlist stops a malicious member from making every recipient fetch an attacker URL (an IP leak).
   - Chat history stores the reference, not the media bytes. A persistent media cache would count as "retaining copies" under the provider's terms; ordinary HTTP caching by the image loader is fine.
   - _Rejected: Mode B with recipient-built URLs_ (was the fallback in this ADR's first version), because the provider forbids reconstructing URLs.
   - _Rejected for provider GIFs: Mode A, encrypted re-upload._ Both providers forbid re-hosting, and an approved cache must purge content on the provider's request, which a server holding opaque blobs it can't identify cannot do.
4. **Encrypted blob format** (for **own GIF uploads**, M3; was Mode A). The sender encrypts the media; recipients fetch from our server only. Blob encryption is implemented **in `crypto/`** (Rust), never per platform in Kotlin:
   1. Pad the **plaintext**: `len (u32 BE) ‖ gif bytes ‖ zero padding`, up to the next size bucket (powers of two from 64 KiB to the max blob size).
   2. Encrypt with AES-256-GCM using a fresh random 256-bit key per blob and a random 96-bit nonce; the blob is `nonce ‖ ciphertext ‖ tag`.
   3. Upload to the blob store, which returns a `blobId`. The MLS message carries `{blobId, key, sha256(uploaded blob bytes), mime, width, height}`.
   4. The recipient checks the sha256 before decrypting, then GCM authentication, then the length prefix: `4 ≤ len + 4 ≤ padded length`, otherwise reject.
   - The uploaded blob size is therefore **bucket size + 28 bytes** (12-byte nonce + 16-byte tag). The server enforces exactly that set of sizes.
5. **Convergent or deduplicating encryption is forbidden.** It would let the server recognise popular GIFs.

## Consequences
- The provider (Klipy) learns the sender's searches and IP, and every recipient's IP when they load the GIF; it can link the two by timing. This is accepted and documented in the threat model; a privacy note goes in the app.
- GIFs the provider removes, or all of them if the provider shuts down (as Tenor did), disappear from chat history and show a placeholder. Provider and slug are kept so a later migration could re-resolve them.
- The URL path pattern is observed, not documented by the provider. If the provider changes it, GIFs show the placeholder until a client update (fails closed).
- The API key is shared by all clients, so its rate limit is too. The free production key is needed before the friends alpha.
- The encrypted blob store is not needed for provider GIFs. Blobs cost storage on the VM, so they get a TTL (e.g. 30 days) and size limits; they serve own GIF uploads (M3).
- Sender-supplied `mime`, `width` and `height` are **untrusted hints**. Clients sniff the real format, accept only GIF/WebP (and MP4 if adopted), and enforce their own limits.
- The client must treat downloaded GIF bytes as untrusted. That means decoding them with platform image decoders only, enforcing size limits, and never rendering provider metadata as HTML.
- The provider API key is embedded in the client and therefore public. That is acceptable for free-tier keys; rate limits apply per key.
