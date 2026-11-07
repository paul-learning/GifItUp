# GifItUp brand assets

The app icon is the **pixel plus** (owner decision, 2026-11-01, #211): five rounded tiles in a plus shape, four in the accent
violet `#8B5CFF`, the right-hand one in light lavender `#F3EEFA`, on a plum gradient `#2E2046` to `#150F20`. Nothing else
carries the mark: no text, no other colours.

## One source
`icon.svg` (viewBox 0 0 100 100, the mark spans 20 to 80) is the source. Everything else comes from it:

| File | What | How it is made |
|---|---|---|
| `androidApp/src/main/res/drawable/ic_launcher_background.xml` | adaptive icon background | the gradient of the SVG as a vector drawable |
| `androidApp/src/main/res/drawable/ic_launcher_foreground.xml` | adaptive icon foreground | the five tiles as vector paths (same coordinates: 100 units are the 108 dp canvas, the mark spans 21.6 to 86.4 dp, inside the 66 dp safe zone) |
| `androidApp/src/main/res/drawable/ic_launcher_monochrome.xml` | themed icon (Android 13+) | the same paths; the four violet tiles at 65% alpha, the light tile at 100%, so it stays distinguishable |
| `androidApp/src/main/res/mipmap-anydpi/ic_launcher.xml`, `ic_launcher_round.xml` | the adaptive icon (minSdk is 28, so no PNG fallbacks) | referenced by `android:icon` and `android:roundIcon` in the manifest |
| `composeApp/src/wasmJsMain/resources/favicon.svg` | web tab icon | `icon.svg` with rounded corners (a tab shows no mask) |
| `composeApp/src/wasmJsMain/resources/favicon-32.png` | web tab icon, PNG fallback | rendered from `favicon.svg` |
| `docs/brand/play-store-icon-512.png` | Play Store icon (512 x 512, 32-bit PNG) | rendered from `icon.svg`; not shipped in the app |

The web favicon files are same-origin, so the page's Content-Security-Policy (`img-src 'self' blob:`) needs no change.

## Rendering the PNGs again
Any renderer that draws an SVG at an exact size works. With the Chromium and Playwright of the dev container (`render.js`
takes the SVG, the size and the output file):

```js
const { chromium } = require('playwright');
const fs = require('fs');
const [,, svgPath, size, out] = process.argv;
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: +size, height: +size }, deviceScaleFactor: 1 });
  const svg = fs.readFileSync(svgPath, 'utf8');
  await page.setContent(`<html><body style="margin:0">${svg.replace('<svg ', `<svg width="${size}" height="${size}" `)}</body></html>`);
  await page.screenshot({ path: out, omitBackground: true, clip: { x: 0, y: 0, width: +size, height: +size } });
  await browser.close();
})();
```

```sh
node render.js docs/brand/icon.svg 512 docs/brand/play-store-icon-512.png
convert docs/brand/play-store-icon-512.png PNG32:docs/brand/play-store-icon-512.png   # Google Play wants 32-bit
node render.js composeApp/src/wasmJsMain/resources/favicon.svg 32 composeApp/src/wasmJsMain/resources/favicon-32.png
```

If the mark changes, change `icon.svg` first, then the three vector drawables (same coordinates), then render the PNGs.
