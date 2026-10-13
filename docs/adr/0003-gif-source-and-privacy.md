# ADR-0003: GIF source and GIF privacy

- **Status:** accepted (delivery mode pending the M0 GIF-provider spike)
- **Date:** 2026-10-13

## Context
- The service must be **free**. It is a hobby project with no paid APIs.
- **The backend must not learn which GIFs were searched for or sent.**
- Free GIF APIs exist, mainly **Giphy** (free API key, attribution required) and **Klipy** (free). Both are third parties that see requests from the client.

## Decision
1. **Search happens client → provider directly**, with the provider API key in the client. Search queries never touch our backend. The provider is hidden behind a `GifProvider` interface so Giphy and Klipy can be swapped.
2. **What our backend sees:** only MLS ciphertext and, depending on the delivery mode, opaque encrypted blobs. It never sees a GIF ID, URL, or search term.
3. **Delivery mode** (the spike picks one based on the provider's terms of service and CORS):
   - **Mode A, encrypted re-upload (preferred if the ToS allows it).** The sender downloads the chosen rendition and encrypts it. Recipients fetch from our server only, so the provider never sees recipients. Blob encryption is implemented **in `crypto/`** (Rust), never per platform in Kotlin:
     1. Pad the **plaintext**: `len (u32 BE) ‖ gif bytes ‖ zero padding`, up to the next size bucket (powers of two from 64 KiB to the max blob size).
     2. Encrypt with AES-256-GCM using a fresh random 256-bit key per blob and a random 96-bit nonce; the blob is `nonce ‖ ciphertext ‖ tag`.
     3. Upload to the blob store, which returns a `blobId`. The MLS message carries `{blobId, key, sha256(uploaded blob bytes), mime, width, height}`.
     4. The recipient checks the sha256 before decrypting, then GCM authentication, then the length prefix: `4 ≤ len + 4 ≤ padded length`, otherwise reject.
     - The uploaded blob size is therefore **bucket size + 28 bytes** (12-byte nonce + 16-byte tag). The server enforces exactly that set of sizes.
   - **Mode B, direct reference.** The MLS message carries **only a provider GIF ID** (validated charset/length), never a URL. Recipients build the media URL themselves from a hard-coded allowlist of provider hosts, then fetch from the provider CDN directly. The provider sees recipients' IPs and the GIF, but our backend still sees nothing. This also stops a malicious member from making every recipient fetch an attacker URL (an IP leak).
4. **Convergent or deduplicating encryption is forbidden.** It would let the server recognise popular GIFs.

## Consequences
- The provider (Giphy/Klipy) learns the sender's searches and IP. This is accepted and documented in the threat model; a privacy note goes in the app.
- Mode A costs storage on the VM, so blobs get a TTL (e.g. 30 days) and size limits. It also gives us "own GIF uploads" (M3) almost for free.
- Sender-supplied `mime`, `width` and `height` are **untrusted hints**. Clients sniff the real format, accept only GIF/WebP (and MP4 if adopted), and enforce their own limits.
- The client must treat downloaded GIF bytes as untrusted. That means decoding them with platform image decoders only, enforcing size limits, and never rendering provider metadata as HTML.
- The provider API key is embedded in the client and therefore public. That is acceptable for free-tier keys; rate limits apply per key.
