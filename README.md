# Stream Player for Android TV

A lightweight media player for Android TV devices.

## Features

- HLS/HTTP streaming support
- Android TV optimized
- Ultra lightweight (~1.4 MB)
- Auto-retry on connection errors
- Fullscreen landscape mode
- Audio sync correction for live streams

## Install

Download the latest APK from [Releases](https://github.com/Chrispsz/tv-minimal/releases) or build from source.

## Usage

1. Open the app
2. Enter a streaming URL
3. Or share a URL from another app

### Supported Formats

- M3U8 / HLS streams
- HTTP/HTTPS video URLs
- RTMP streams

## Build

```bash
git clone https://github.com/Chrispsz/tv-minimal.git
cd tv-minimal
./gradlew assembleRelease
```

## Technical

| Spec | Value |
|------|-------|
| Min SDK | 21 (Android 5.0) |
| Target SDK | 34 (Android 14) |
| APK Size | ~1.4 MB |
| Dependencies | Media3 ExoPlayer |

## License

MIT
