<div align="center">

# 🎬 WOW Drama TV

**A TV-optimized streaming companion for [wow-drama.com](https://wow-drama.com) — built for Chromecast with Google TV**

[![Release](https://img.shields.io/github/v/release/Thanatad/WOW-Drama-TV?color=E50914&label=Release&logo=github)](https://github.com/Thanatad/WOW-Drama-TV/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%2F%20Google%20TV-3DDC84?logo=android&logoColor=white)](https://www.android.com/tv/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Auto Update](https://img.shields.io/badge/In--App%20Updater-%E2%9C%93%20GitHub--backed-00D2FF)](app-update.json)

[Download the latest APK](https://github.com/Thanatad/WOW-Drama-TV/releases/latest/download/WOW-Drama-TV.apk) ·
[Report an issue](https://github.com/Thanatad/WOW-Drama-TV/issues)

</div>

---

## ✨ Highlights

| | Feature | Description |
|---|---|---|
| 📺 | **Made for the D-pad** | Every screen is fully navigable with the stock Google TV remote — no touch required |
| ⚡ | **Lag-free browsing** | Cached spatial navigation + debounced DOM observers keep card scrolling smooth even on low-end CPUs (yes, the little Chromecast puck) |
| 🛡️ | **Built-in AdBlock** | Thai ad-filter list, network-level request blocking, VAST video-ad skipping, and popup protection |
| 🍿 | **Cinema fullscreen** | Double-press **OK** to jump into a 100% fullscreen player — no grey veils, no paused screens |
| 🔍 | **Remote search** | Quick search overlay reachable from the top bar |
| 🔄 | **Self-updating** | The app checks a GitHub-hosted manifest and downloads/installs new versions by itself |
| 🇹🇭 | **Thai-first UX** | Auto-selects Thai dub, native Thai interface text |

| 🚀 | **Native Netflix-style browse** | Poster grid rendered natively (no WebView) — instant category switching, smooth D-pad scrolling; WebView is used only for the detail/player page |

## 🎛️ Remote Controls

| Key | Action |
|---|---|
| **D-pad** | Move between movie cards / episode buttons, or scroll the page |
| **OK / Enter** | Select • **Double-press OK** = enter cinema fullscreen |
| **Back** | Go back • **Double-press** = exit app |
| **OK (in player)** | Toggle play / pause |
| **Left / Right (in player)** | Seek ±10 seconds |

## 📲 Installation

1. Grab the newest APK from the
   [**Releases** page](https://github.com/Thanatad/WOW-Drama-TV/releases/latest/download/WOW-Drama-TV.apk)
   (or let the app update itself — see below).
2. Sideload it onto your TV box (e.g. `adb install WOW-Drama-TV.apk`, or via any
   "Downloader"-style TV app).
3. Open **WOW Drama** from the Google TV launcher. First launch asks for
   *"install unknown apps"* permission only when updating itself — nothing else.

## 🔄 In-App Update System

The app polls [`app-update.json`](app-update.json) on `main` (via
`raw.githubusercontent.com`), compares `versionCode`, then offers a one-press
update: **download with live progress → launch system installer → done.**

Releasing a new version is fully automated — just bump the version and push a tag:

```bash
# 1. Edit app/build.gradle.kts
#      versionCode = 2
#      versionName = "1.1"

# 2. Commit + tag
git add -A && git commit -m "v1.1"
git tag v1.1
git push origin main v1.1
```

The [release workflow](.github/workflows/release.yml) then:

1. 🔨 Builds the APK on GitHub Actions (signed with the repo's `debug.keystore`
   so updates install over the existing app)
2. 🚀 Creates a GitHub Release and attaches `WOW-Drama-TV.apk`
3. ✍️ Rewrites `app-update.json` with the new version + APK URL and commits it
   back to `main`

> ⚠️ **Signing note:** updates must be signed with the same key as the installed
> app. This repo intentionally commits `debug.keystore` to keep CI builds
> consistent — fine for a debug-key-distributed app, but don't reuse this key
> for anything sensitive.

## 🗂️ Project Structure

```
app/src/main/java/com/example/
├── BrowseActivity.kt          # Native Netflix-style browse grid (launcher)
├── browse/
│   └── WowScraper.kt          # Jsoup scraper for listing pages (cards + pagination)
├── MainActivity.kt            # WebView host: detail/player page, overlays, key routing, update UI
├── adblock/
│   └── AdBlockManager.kt      # Filter parsing, CSS/JS injection, TV layout theme
├── tv/
│   └── TvRemoteController.kt  # D-pad → spatial navigation & page scrolling (cached)
└── update/
    └── UpdateManager.kt       # Manifest fetch, APK download, installer launch
```

## 🛠️ Building Locally

```bash
git clone https://github.com/Thanatad/WOW-Drama-TV.git
cd WOW-Drama-TV
./gradlew assembleDebug        # output: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 + an Android SDK (`local.properties` with `sdk.dir` is read
automatically by the Android Gradle plugin).

## 🧭 How It Works

```mermaid
flowchart LR
    A[TV Remote] -->|KeyEvents| B[BrowseActivity<br/>native poster grid]
    B -->|Jsoup scrape| C[(wow-drama.com<br/>listing pages)]
    B -->|open detail| D[MainActivity<br/>WebView]
    D --> E[TvRemoteController<br/>spatial nav JS]
    E --> F[WebView player<br/>detail / episode page]
    F --> G[AdBlockManager<br/>request blocking + CSS/JS injection]
    D --> H[UpdateManager]
    H -->|fetch manifest| I[(GitHub Releases)]
    I -->|APK| J[System Installer]
```

- **Browsing is native**: listing pages are scraped with Jsoup into a
  RecyclerView poster grid (5 columns) — fast to open, smooth to scroll,
  and D-pad friendly. "Load more" follows the site's `rel=next` pagination.
- **Playing stays in WebView**: tapping a card opens the WebView only for
  the drama detail / episode / player page, where the existing cinema
  fullscreen, auto-play and remote-controlled player logic apply.

- **Key events** are intercepted before the WebView and translated into a
  lightweight, *cached* spatial-navigation script — repeated presses reuse the
  previous element scan, so movement stays responsive on weak TV CPUs.
- **The website itself** is restyled at runtime into a dark "Google TV" grid
  (5–6 poster columns, big focus glow, episode buttons optimized for remotes).
- **Ads** are blocked at three layers: network interception, CSS hiding, and a
  debounced DOM mutation observer.

---

<div align="center">

Made with ❤️ for Thai drama lovers ·
Unofficial fan app for wow-drama.com

</div>
