# IPLINKS Player

Minimalist IPTV player for Android TV.

## Features

- 🎬 HLS/M3U8 streaming support
- 📺 Android TV optimized
- ⚡ Ultra lightweight (~1.4 MB)
- 🔄 Auto-retry on connection errors (max 3 attempts)
- 🔲 Fullscreen landscape mode
- 🛡️ Zero memory leaks architecture

## CI/CD Pipeline

![CI](https://github.com/Chrispsz/tv-minimal/workflows/CI%20-%20Build%20%26%20Test/badge.svg)

A cada push, o GitHub Actions executa:
- 🔍 **Detekt** - Análise estática de código
- 🧪 **Unit Tests** - Testes automáticos
- 🏗️ **Build APK** - Compilação do APK

### Relatórios

Os artefatos são disponibilizados em cada run do workflow:
- `test-reports` - Relatórios de testes (HTML/XML)
- `detekt-reports` - Relatórios de análise estática
- `debug-apk` - APK para instalação

## Install

Download the latest APK from [Releases](https://github.com/Chrispsz/tv-minimal/releases) ou baixe o artifact do GitHub Actions.

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

# Build com testes automáticos
./gradlew assembleDebug

# Apenas testes
./gradlew testDebugUnitTest

# Análise completa (testes + detekt + lint)
./gradlew check

# Ver relatório de testes
open app/build/reports/tests/testDebugUnitTest/index.html
```

## Architecture

### Memory Leak Prevention

| Technique | Implementation |
|-----------|----------------|
| **lifecycleScope** | Automatic coroutine cancellation on destroy |
| **Sealed Classes** | Exhaustive state handling with `when` |
| **Listener Cleanup** | All listeners removed in `onDestroy` |
| **SurfaceView Reset** | `setVideoSurfaceView(null)` before release |
| **LeakCanary** | Debug build detection for leaks |

### HLS Optimization

| Parameter | Value | Purpose |
|-----------|-------|---------|
| MIN_BUFFER_MS | 10,000 | Minimum buffer (10s) |
| MAX_BUFFER_MS | 25,000 | Maximum buffer (25s) |
| targetOffsetMs | 3,000 | Live stream latency |
| Min/Max Speed | 0.97-1.03x | Auto-adjust for live sync |

## Technical

| Spec | Value |
|------|-------|
| Min SDK | 21 (Android 5.0) |
| Target SDK | 34 (Android 14) |
| APK Size | ~1.4 MB |
| Dependencies | 5 (Media3, Lifecycle, LeakCanary-debug) |

## License

MIT
