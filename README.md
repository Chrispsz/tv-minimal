# IPLINKS Player

Minimalist IPTV player for Android TV.

## Features

- 🎬 HLS/M3U8 streaming support
- 📺 Android TV optimized
- ⚡ Ultra lightweight (~1.4 MB)
- 🔄 Auto-retry on connection errors
- 🔲 Fullscreen landscape mode

## Install

Download the latest APK from [Releases](https://github.com/Chrispsz/tv-minimal/releases) or build from source.

## Usage

1. Open the app
2. Enter a streaming URL (M3U8, HLS)
3. Or share a URL from another app

### Supported Formats

- M3U8 / HLS streams
- HTTP/HTTPS video URLs
- RTMP streams

## Build

```bash
git clone https://github.com/Chrispsz/tv-minimal.git
cd tv-minimal
./gradlew assembleDebug
```

## Technical

| Spec | Value |
|------|-------|
| Min SDK | 21 (Android 5.0) |
| Target SDK | 34 (Android 14) |
| APK Size | ~1.4 MB |
| Dependencies | 3 (Media3 only) |

## License

MIT
