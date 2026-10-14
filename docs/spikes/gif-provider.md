# Spike: free GIF provider and delivery mode

- **Issue:** #5
- **Date:** 2026-10-14
- **Status:** done. **Klipy**, delivered by **direct reference (Mode B′)**: the MLS message carries the media URL exactly as Klipy returned it, and recipients validate it strictly before fetching.
- **Code:** none committed. The `GifProvider` sketch below is spike-quality; the real implementation is part of #23 (picker) and #24 (send/receive).
- **Decision record:** ADR-0003 (updated in this PR).

## Question
Which free GIF API do we use? Can the sender re-upload an encrypted copy (Mode A), or must recipients fetch from the provider CDN (Mode B)? Hard constraints (ADR-0003): it's free, and our backend never learns which GIF was searched or sent.

## Candidates
**Tenor is gone.** Google closed new sign-ups in January 2026 and shut the API down on 2026-06-30. So the real choice is Giphy vs Klipy.

| | **Klipy** | **Giphy** |
|---|---|---|
| Cost | Free. Test key: 100 requests/hour. **Production key: unlimited, free**, requested via the Partner Panel | Free beta key: 100 requests/hour, meant for development. **Production key is paid**; price negotiated after an application |
| Re-hosting / caching media | **Forbidden** without written approval ("Do not store, mirror, re-host, rewrite, or retain copies"). Approved caches must support revalidation and purge | **Forbidden** without written approval ("Do not cache media URLs or copies of GIPHY media assets"). Approved caches must follow Giphy's revalidation |
| URLs | "Use media URLs … as provided. Do not remove, alter, replace, or **reconstruct** URL parameters, content identifiers …" | "Do not strip or modify URLs returned by the API" |
| Request origin | API calls and media loads must come from the end-user client; no proxies | Same: "All requests to GIPHY should be made directly from the client side" |
| Attribution | **Required:** "Search KLIPY" as the search placeholder. Optional: "Powered by KLIPY" mark, watermark | **Required:** conspicuous "Powered By GIPHY" |
| Result handling | Show Search/Trending results in the order given, don't filter them ourselves (filtering is configured in the Partner Panel); keep Klipy results separate from other providers | — |
| Ads | Only if enabled for the key in the Partner Panel (objects with `type: "ad"`). We leave them **off** | — |
| User identifiers | `customer_id` is optional. We **don't send it** | — |
| Formats | `gif`, `webp`, `mp4`, `webm`, `jpg` in sizes `xs`/`sm`/`md`/`hd` | `gif`, `webp`, `mp4` in many renditions |
| CORS (tested live) | `api.klipy.com`: `Access-Control-Allow-Origin: *`, preflight allows all. `static.klipy.com`: ACAO `*`, no `Cross-Origin-Resource-Policy` header | `api.giphy.com` and `media*.giphy.com` / `i.giphy.com`: ACAO `*`, CORP `cross-origin` |

**Other options looked at** (after the owner asked whether Klipy is really the only one):

| Option | Free? | Key | Verdict |
|---|---|---|---|
| Imgur API | Free for non-commercial use, ~12,500 requests/day | Client ID per app, embedded in the client (same problem as any key) | Community uploads, not a reaction-GIF library. Weaker than Klipy |
| Openverse (openly licensed media) | Yes | Without a key: 5 requests/hour; with a key: same problem | Hardly any reaction GIFs, and too slow without a key |
| Wikimedia Commons | Yes | None | Technically fine (no key, CORS), but practically no reaction GIFs |
| Own curated collection on our server | Yes | None | Most private: every client gets the whole catalogue, so nobody learns what was searched or sent. But we'd have to find and license the content ourselves, and the selection stays small. Kept as a later fallback (ROADMAP "Later / ideas") |

Every hosted provider needs an app key used from the client, and Giphy and Klipy forbid routing requests through our server. So a key reaching clients is unavoidable. What we control is **which** clients get it (see "The API key").

**Pick: Klipy.** It is the only option that stays free in production. Giphy would work technically, but a release build on a beta key goes against its intent, and production access costs money.

Klipy media URLs look like `https://static.klipy.com/ii/<32 hex>/<2 hex>/<2 hex>/<name>.<ext>`. They carry no query string and no user or session identifier, which matters for Mode B′ below.

Typical Klipy GIF sizes (KiB; mean / median, from Klipy's format table):

| Size | gif | webp | mp4 |
|---|---|---|---|
| xs | 101 / 64 | 51 / 35 | 37 / 31 |
| sm | 330 / 206 | 178 / 117 | 98 / 85 |
| md | 2263 / 1405 | 988 / 636 | 444 / 257 |
| hd | 3874 / 2578 | 755 / 288 | 492 / 295 |

## Delivery mode
### Mode A (encrypted re-upload): rejected
Both providers forbid storing or re-hosting media unless they approve it in writing. Asking would not help: an approved cache must revalidate and purge removed content on the provider's instructions. Our server only holds opaque, padded blobs and **must not know which GIF a blob is**, so it can't purge one. Mode A and E2EE GIF privacy are incompatible under these terms.

The encrypted-blob design stays in ADR-0003 for **own GIF uploads** (M3), which aren't provider content.

### Mode B as written in ADR-0003: not compliant
ADR-0003 had the message carry only a GIF ID, and recipients **build the URL** from it. Klipy explicitly forbids reconstructing URLs or their identifiers.

### Mode B′ (chosen): forward the returned URL, validate strictly
1. The sender's client picks a rendition from the search result. Default: `md` **webp**, falling back to `md` gif. It sends `{provider: "klipy", slug, url, width, height}` inside the MLS application message. The final wire shape is defined in #24.
2. Every client (sender **and** recipient) treats the message fields as untrusted. Anything that fails these checks shows a "GIF unavailable" placeholder, and **nothing is fetched**:
   - **`url`:** the **raw string** must match this regex as a **full-string match** (Kotlin `Regex.matches`; a `$` that allows a trailing newline isn't enough), case-sensitive, with no normalisation first:
     `^https://static[12]?\.klipy\.com/ii/[0-9a-f]{32}/[0-9a-f]{2}/[0-9a-f]{2}/[A-Za-z0-9_-]{1,64}\.(gif|webp)$`
     That alone pins the scheme, the host (exactly one of Klipy's published media hosts `static.klipy.com`, `static1.klipy.com` and `static2.klipy.com`), no user info, no port, no query, no fragment, and bounds the length. Then the URL is parsed with the platform parser, and the **re-serialised URL must equal the input exactly**. That catches parser differences such as uppercase hosts, trailing dots, percent-encoding or IDNA. Matching against a regex first and parsing second avoids validating one string and fetching another.
   - **`provider`:** must be exactly `"klipy"`.
   - **`slug`:** must match `^[A-Za-z0-9_-]{1,128}$`. It's shown, if at all, as plain text only.
   - **`width` / `height`:** layout hints only, integers from 1 to 2048. Anything else gets a placeholder (see 4 for the real checks).
3. **Fetch:** no cookies and no `Referer`. Web: `credentials: "omit"`, `referrerPolicy: "no-referrer"`. **Redirects are never followed**: `redirect: "error"` on the web, `followRedirects = false` in Ktor/OkHttp on Android. A browser can't inspect a redirect target, so "follow only if it passes the check" isn't implementable.
4. **Decode (zero-click attack surface, see below):** the response is capped at 8 MiB. The format is sniffed from the magic bytes; only GIF and WebP are accepted. **Before** any native decoder sees the bytes, our own **bounded, pure-Kotlin structure scanner** walks the file and checks it. GIF and WebP don't store a frame count in a header, so the scanner counts frames by walking the GIF blocks or the RIFF/`ANMF` chunks, with a hard limit on steps. It reads the real canvas size, **not** the sender's hints, and enforces the limits (starting values for #24: ≤ 2048 px per side, ≤ 1000 frames, decoded size ≤ 128 MiB = width × height × 4 × frames). A malformed or truncated structure means a placeholder. Which decoder then runs:
   - **Android, API 28+:** the OS `ImageDecoder` / `AnimatedImageDrawable`. The codecs behind them (Skia, libwebp, giflib) are **not** Mainline modules; fixes arrive only with the device's **OEM security patches**. Phones out of support never get them: per public reporting, the CVE-2023-4863 fix only reached Android 11–13. So #24 checks `Build.VERSION.SECURITY_PATCH`. If the patch level is older than 12 months, received GIFs are **tap-to-load** (no zero-click decoding) and the app says why.
   - **Android, API 26–27** (current `minSdk` is 26): there is no OS decoder for animated WebP. **Recommendation for #24: raise `minSdk` to 28.** Otherwise only the first frame is shown, decoded by the OS (`BitmapFactory`).
   - **Web:** Compose Multiplatform on wasmJs decodes with **Skiko**, which is Skia compiled to wasm with its **own** GIF/WebP codecs, not the browser's. **M2 default: the browser decodes.** Either WebCodecs `ImageDecoder` hands frames to Compose, or the GIF is shown as an HTML `<img src="blob:…">` overlay. Browsers patch their codecs themselves. Skiko decoding of received GIFs is used only if #24 finds both browser approaches impractical. In that case: the owner watches Compose Multiplatform releases and GitHub advisories for Skia/libwebp/giflib (the milestone security review checks this too), ships a Compose update within 14 days of a codec CVE, and **if no fixed Skiko is available by then, the web client switches received GIFs to tap-to-load** until it is.
   - **Web loading:** media is loaded with `fetch()` (`redirect: "error"`, `credentials: "omit"`, `referrerPolicy: "no-referrer"`, streamed with the 8 MiB cap) and then shown from a `blob:` URL, never through a plain `<img src>` pointing at Klipy, which would skip all of these checks.
5. Chat history stores the message (URL and slug), not the media bytes. Normal HTTP caching by the client's image loader (Klipy sends `Cache-Control: public, max-age=86400`) is fine. A persistent media cache is not, because it would count as "retaining copies".

Why this keeps ADR-0003's security goals:
- **The backend still sees nothing.** The URL is inside MLS ciphertext, and the fetch goes from the client straight to Klipy's CDN.
- **A malicious member can't make recipients fetch an attacker URL** and so learn their IPs. The host allowlist only allows Klipy's CDN, and Klipy already sees these fetches by design.
- **The URL carries no sender identifier.** Klipy URLs have no query string, and we don't send `customer_id`.

What it costs (recorded in the threat model):
- Klipy, and Cloudflare in front of its CDN, see every recipient's IP fetching the GIF. Klipy can link the sender's search to those fetches by timing.
- **Zero-click decoder exposure.** Every recipient automatically fetches and decodes media at a path the sender chose. A decoder bug (libwebp CVE-2023-4863 is the precedent) would run in the process that holds the MLS keys. Hostile bytes could come from Klipy/Cloudflare, from anyone who can get content onto Klipy's CDN, or, if a member found such a way, from a malicious member. Mitigations: decoders that get security updates (OS/browser, step 4), tap-to-load on Android phones with an old security patch level, and patched decoders as an explicit assumption. The structure scanner and limits stop resource exhaustion and malformed containers; they **can't** catch bugs inside the compressed image data, which is where CVE-2023-4863 was. Mode A wouldn't have helped here, because the sender would choose the bytes outright.
- **The media isn't bound to the message.** MLS authenticates the URL, not the bytes. Klipy or its CDN can serve different content over time, or per recipient IP. Mode A's sha256 binding is lost.
- **A malicious member could learn who loaded a GIF, and when.** That works if Klipy offers uploaders view analytics and the member uploads a GIF there; it isn't verified whether Klipy offers such analytics. Their own membership already shows them that the message was delivered, so the extra leak is load timing.
- If Klipy removes a GIF or shuts down (like Tenor), it disappears from chat history, and the placeholder shows instead. That's accepted; slug and provider are kept so a later migration could re-resolve them.
- The path pattern is based on observed URLs, not on documentation. If Klipy changes its URL layout, GIFs show the placeholder until the client is updated. That fails closed, which is the safe direction.

The alternative, sending only the slug and having each recipient call Klipy's Items API, was rejected. It costs one API call per received GIF against the rate limit, and the URL it returns still has to pass the same check.

## Rate limits
All clients use the same key, so **all users share one limit**. In testing mode that's 100 requests/hour: one per opened picker (trending) plus one per debounced search. That's enough for development, but not for the friends alpha. **The production key must be requested before #27.** The client debounces search input (≥ 400 ms) and shows a friendly message on HTTP 429.

## The API key (decided with the owner, 2026-10-14)
- **Not in the build.** The key is never in the repo, the APK or the web bundle. Those are public (GitHub Releases, a web app anyone can load), so anything baked in can be extracted by anyone.
- **Our server hands it to authenticated clients only.** The server reads the key from its environment (a deploy secret) and returns it from an authenticated endpoint. Clients keep it **in memory only** and use it for direct calls to Klipy.
  - **When clients fetch it (CR-7, SEC-10):** together with the normal startup/login traffic, and refreshed on a fixed schedule (e.g. every 6 h while running). **Never** lazily when the picker opens, and never in reaction to a Klipy 401/403. Either of those would tell the server when someone is about to send a GIF. If Klipy rejects the key, the picker shows "GIF search temporarily unavailable" until the next scheduled refresh.
  - **Clients don't trust the response (SEC-7):** the provider is hard-coded in the client (`klipy`), and any provider value from the server is ignored. The key must match a strict charset and length (starting rule `^[A-Za-z0-9_-]{16,128}$`; #23 confirms it against the real key format), and it is inserted as one percent-encoded path segment.
  - Only invited users ever see the key. Leaking it beyond the circle needs one of them to extract and share it.
  - Rotation is a server config change that takes effect at the next scheduled refresh. No rebuild or release is needed.
  - An **honest** server learns nothing about GIF use: it only hands out the key, and searches and media loads still go straight from the client to Klipy.
  - **A malicious server could hand each user a different key** from Klipy accounts the operator controls. If Klipy's Partner Panel shows per-key analytics (not verified), the operator could then tie search terms to individual users. An APK-embedded key wouldn't have this problem, but it would be public. This is recorded as a limitation against an active server, in the threat model. A later mitigation: group members compare a hash of their key over MLS (ROADMAP "Later / ideas").
  - New server-visible data: none about users. The server stores one more secret (the key) and sees when clients fetch it (at login/startup, along with other traffic). The response gets `Cache-Control: no-store`, and the key is never logged (CLAUDE.md rule 4).
  - Implemented in a separate issue (server endpoint, wire model in `protocol/`), which #23 depends on.
- **Residual availability risk:** a member could extract and abuse the key, and Klipy could revoke it. GIF search and trending then stop for everyone until the key is rotated; already-sent GIFs still load, because media fetches don't use the key. With the production key there is no hourly limit to exhaust. Runbook: create a new key in the Partner Panel, revoke the old one, update the server's secret and restart it.
- Partner Panel settings: ads **off**, content filter set by the owner. The client **also sends `content_filter` on every request** (SEC-15), so a key from a different account (e.g. one handed out by a malicious server) can't switch the filter off. In case ads are ever switched on, the client renders `type: "ad"` items as plain placeholders and never loads their content or calls their tracking URLs.
- **Web CSP (deployment, #6/#27):** `connect-src` lists `https://api.klipy.com https://static.klipy.com https://static1.klipy.com https://static2.klipy.com` for the GIF features. `img-src` is `'self' blob:` and does **not** list Klipy's hosts, so a plain `<img>` can't bypass the fetch checks. Klipy's media hosts send no `Cross-Origin-Resource-Policy` header, so the web app must not enable `Cross-Origin-Embedder-Policy: require-corp` without testing it.

## `GifProvider` sketch (spike-quality, for #23)
```kotlin
// shared/commonMain, data layer. Not committed in this spike.
interface GifProvider {
    val id: String // "klipy"
    suspend fun trending(page: Int = 1): GifPage
    suspend fun search(query: String, page: Int = 1): GifPage
}

data class GifPage(val items: List<GifItem>, val hasNext: Boolean)

data class GifItem(
    val slug: String,
    val preview: GifMedia, // sm webp, for the picker grid
    val send: GifMedia,    // md webp (fallback md gif): what goes into the message
)

data class GifMedia(val url: String, val width: Int, val height: Int)

// Shared by picker, sender and receiver; null means "show placeholder, fetch nothing".
fun interface GifUrlPolicy { fun validate(url: String): String? }
```
- Klipy: `GET https://api.klipy.com/api/v1/{app_key}/gifs/trending|search?q=&page=&per_page=&format_filter=gif,webp`. The response items carry `slug` and `file.{xs,sm,md,hd}.{gif,webp,…}.{url,width,height,size}`.
- Items whose URLs fail validation become placeholder tiles, not dropped results, because Klipy requires keeping the result order and composition.
- Attribution: "Search KLIPY" as the search placeholder.

## Not verified
- **No live API call with a real key**: the owner registers it. Request and response shapes come from Klipy's published docs (`docs.klipy.com/llms-full.txt`). #23 confirms them, including the shape of `md`/`sm` webp URLs.
- That every Klipy media URL follows the path pattern. It's derived from all example URLs in the docs and one live CDN fetch (`static.klipy.com` returned `200 image/gif` with ACAO `*`).
- Animated WebP decoding on all our Android API levels and browsers; that's for #23/#25.

## Sources
- Klipy: [integration requirements](https://docs.klipy.com/integration-requirements.md), [attribution](https://docs.klipy.com/attribution.md), [getting started](https://docs.klipy.com/getting-started.md), [network allowlist](https://docs.klipy.com/network-requirements.md), [GIF search API](https://docs.klipy.com/gifs-api/gifs-search-api.md), [format sizes](https://docs.klipy.com/gifs-api/gifs-format-sizes.md), [ads](https://docs.klipy.com/advertisements.md)
- Giphy: [API docs](https://developers.giphy.com/docs/api/), [best practices](https://developers.giphy.com/docs/api/best-practices/)
- Tenor shutdown: [9to5Google, 2026-06-30](https://9to5google.com/2026/06/30/google-tenor-api-gif-updates/)
- CORS: `curl` with an `Origin` header against `api.klipy.com`, `static.klipy.com`, `api.giphy.com`, `media.giphy.com`, `media0.giphy.com`, `i.giphy.com` on 2026-10-14.
