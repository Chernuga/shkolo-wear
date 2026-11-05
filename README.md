# Shkolo for Wear OS

The [shkolo-ui](https://github.com/Chernuga/shkolo-ui) experience, rebuilt as a native
Android **phone + watch** pair.

The watch shows your grades and timetable. The **phone** does all the Shkolo work —
handshake, fingerprinting, fetching — so no cloud Worker and no browser-time budget is
consumed.

## How it works

```
┌─────────────────────┐            ┌──────────────────────────────┐
│  Wear OS watch      │  Message   │  Android phone               │
│  (Compose for Wear) │ ─────────► │  ShkoloClient                │
│                     │  API       │   ├─ hidden WebView ────────┐│
│  grades / schedule  │ ◄───────── │   │   fp.js builds the     ││
│  UI only            │   JSON     │   │   fingerprint, login   ││
└─────────────────────┘            │   └──────── cookies ─────────┘│
                                   │   └─ plain HTTP reuse (cheap) │
                                   └───────────────┬──────────────┘
                                                   ▼
                                            app.shkolo.bg
```

**Why a WebView on the phone?** Shkolo's login needs a real browser: JavaScript
fingerprinting (canvas/audio/WebGL) plus Cloudflare's challenge. A watch cannot do that.
An Android WebView *is* a real Chromium renderer, so it succeeds where plain HTTP fails —
this is the same reason Cloudflare Browser Run was needed before. Cookies are readable via
`CookieManager`, so the session can then be reused for cheap plain-HTTP calls.

**Transport cheapness.** Every watch request first tries a plain HTTPS GET carrying the
harvested cookies. Only when Shkolo answers with a login wall or "Page Expired" does the
phone fall back to driving the WebView. In practice a warm session means the WebView goes
unused.

## Modules

| Module | Type | Purpose |
|---|---|---|
| `common` | Kotlin JVM | Wire protocol + ports of `parse.js` / `parse-schedule.js` |
| `phone` | Android app | WebView session, HTTP reuse, answers the watch |
| `wear` | Wear OS app | Compose UI — grades list and swipeable day columns |

The parsers are **behaviour-identical ports** of the web ones and are covered by JUnit
tests against the same real Shkolo HTML the JavaScript was verified on:

```
grades   → 24 subjects, 25 grades, average 5.68
schedule → 5 days, 32 lessons
```

## Using it

1. Install both APKs (`phone` on the phone, `wear` on the watch). Both must be
   signed with the same key and share the package prefix for the Wearable Data
   Layer to work.
2. Open **Shkolo** on the phone, log in once. The password is used for that single
   login and never stored.
3. Open **Shkolo** on the watch. It asks the phone for `/grades` and `/schedule`.

The session lasts ~3 hours (Shkolo's cookie lifetime). After that the watch shows
"Влезте в приложението на телефона" and you log in again on the phone.

## Protocol

Watch → phone, JSON on `/shkolo/request`:

```json
{ "id": "<uuid>", "path": "/grades", "query": "subject=език" }
```

Phone → watch, JSON on `/shkolo/response`:

```json
{ "id": "<uuid>", "status": 200, "body": "{\"subjects\": …}" }
```

Only allow-listed paths are served (`Protocol.ALLOWED_PATHS`), so the watch cannot make
the phone fetch arbitrary URLs.

## Security notes

- The watch **never** sends credentials; login lives entirely on the phone.
- The watch sends only a path and a query — no cookies cross the link.
- `common.Protocol.isAllowed` restricts what a compromised watch could reach.
- Cookies live in the app's `CookieManager`; `allowBackup=false` keeps them out of backups.

## Building

```bash
./gradlew :common:test        # parser tests against real HTML
./gradlew :phone:assembleDebug
./gradlew :wear:assembleDebug
```

Requirements: JDK 17+, Android SDK 35, `ANDROID_HOME` set.

## Known limitations

- **Not verified on hardware.** The code compiles and the parsers are tested, but no
  emulator image or physical Wear device was available in the build environment, so the
  round-display layout and the Bluetooth round trip are unproven.
- The WebView login flow is the same shape we verified via Playwright, but Shkolo's
  anti-bot behaviour on a phone WebView specifically has not been exercised.
- `phone` and `wear` must be built/signed together; a debug build is fine for testing.

## Related

- [shkolo-ui](https://github.com/Chernuga/shkolo-ui) — the web version this ports
- The original Worker implementation lives in the Shkolo project folder
