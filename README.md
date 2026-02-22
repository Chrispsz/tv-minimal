# Stream Player for Android TV

A lightweight media player for Android TV devices.

## Features

- HLS/HTTP streaming support
- Android TV optimized (Leanback launcher)
- Ultra lightweight (~1.4 MB)
- Auto-recovery from streaming errors
- Fullscreen landscape mode
- Audio sync correction for live streams

## Install

Download the latest APK from [Actions](https://github.com/Chrispsz/tv-minimal/actions) or build from source.

## Usage

The app receives streaming URLs via Android intents:

### Methods

1. **Share URL** - Share a video URL from any app to IPLINKS
2. **Open Link** - Tap a video/streams link in a browser
3. **Custom Scheme** - Use `iplinks://play?url=STREAM_URL`
4. **External App** - Send intent with `stream_url` extra

### Example (ADB)
```bash
adb shell am start -a android.intent.action.VIEW \
  -d "iplinks://play?url=https://example.com/stream.m3u8"
```

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

APK output: `app/build/outputs/apk/release/`

## Technical

| Spec | Value |
|------|-------|
| Min SDK | 21 (Android 5.0) |
| Target SDK | 34 (Android 14) |
| APK Size | ~1.4 MB |
| Dependencies | Media3 ExoPlayer |

## License

MIT
