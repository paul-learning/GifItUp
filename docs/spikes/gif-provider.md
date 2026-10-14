# Spike: free GIF provider and delivery mode

- **Issue:** #5
- **Date:** 2026-10-14
- **Status:** done. **Klipy**, delivered by **direct reference (Mode B)**: the MLS message carries the media URL exactly as Klipy returned it, and recipients validate it strictly before fetching.
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

**Pick: Klipy.** It is the only option that stays free in production. Giphy would work technically, but a release build on a beta key goes against its intent, and production access costs money.

Klipy media URLs look like `https://static.klipy.com/ii/<32 hex>/<2 hex>/<2 hex>/<name>.<ext>`. They carry no query string and no user or session identifier, which matters for Mode B below.

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
2. Every client (sender **and** recipient) treats the URL as untrusted and accepts it only if all of these hold. Otherwise it shows a "GIF unavailable" placeholder and **fetches nothing**:
   - It parses as an absolute URL with scheme `https`, no user info, no port (or 443), no query, no fragment, and is at most 256 characters.
   - The host is **exactly** one of `static.klipy.com`, `static1.klipy.com`, `static2.klipy.com` (Klipy's published media hosts).
   - The path matches `^/ii/[0-9a-f]{32}/[0-9a-f]{2}/[0-9a-f]{2}/[A-Za-z0-9_-]{1,64}\.(gif|webp)$`.
3. The fetch sends no cookies and no `Referer`. Web: `credentials: "omit"`, `referrerPolicy: "no-referrer"`. Redirects are not followed unless the target passes the same check. The response is capped at 8 MiB, and the format is sniffed: only GIF and WebP are decoded, with the platform decoders.
4. Chat history stores the message (URL and slug), not the media bytes. Normal HTTP caching by the client's image loader (Klipy sends `Cache-Control: public, max-age=86400`) is fine. A persistent media cache is not, because it would count as "retaining copies".

Why this keeps ADR-0003's security goals:
- **The backend still sees nothing.** The URL is inside MLS ciphertext, and the fetch goes from the client straight to Klipy's CDN.
- **A malicious member can't make recipients fetch an attacker URL** and so learn their IPs. The host allowlist only allows Klipy's CDN, and Klipy already sees these fetches by design.
- **The URL carries no sender identifier.** Klipy URLs have no query string, and we don't send `customer_id`.

What it costs (recorded in the threat model):
- Klipy (behind Cloudflare) sees every recipient's IP fetching the GIF, and can link the sender's search to those fetches by timing.
- If Klipy removes a GIF or shuts down (like Tenor), it disappears from chat history, and the placeholder shows instead. That's accepted; slug and provider are kept so a later migration could re-resolve them.
- The path pattern is based on observed URLs, not on documentation. If Klipy changes its URL layout, GIFs show the placeholder until the client is updated. That fails closed, which is the safe direction.

The alternative, sending only the slug and having each recipient call Klipy's Items API, was rejected. It costs one API call per received GIF against the rate limit, and the URL it returns still has to pass the same check.

## Rate limits
The key ships inside every client, so **all users share one limit**. In testing mode that's 100 requests/hour: one per opened picker (trending) plus one per debounced search. That's enough for development, but not for the friends alpha. **The production key must be requested before #27.** The client debounces search input (≥ 400 ms) and shows a friendly message on HTTP 429.

## The API key
- The key isn't secret in the cryptographic sense: it's embedded in every APK and web bundle. But CLAUDE.md rule 3 keeps it **out of the repo**. Builds read it from `local.properties` / an env var locally and from a GitHub secret in CI. Wiring that up is part of #23.
- Partner Panel settings: ads **off**, content filter set by the owner (Klipy's `content_filter` request parameter is the per-request alternative).

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
