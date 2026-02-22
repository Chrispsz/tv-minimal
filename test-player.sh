#!/bin/bash

# ========================================
# IPLINKS Player - Teste Rápido
# ========================================

# Cores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

STREAM_URL="http://209.131.122.136/live/j5z6h3pb/mv67p015c/6589.m3u8?token=WFhmeDdCR0p4eFMwaGdJ"
DEVICE=""

banner() {
    echo ""
    echo -e "${CYAN}╔════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║      IPLINKS Player - Teste Rápido         ║${NC}"
    echo -e "${CYAN}╚════════════════════════════════════════════╝${NC}"
    echo ""
}

detect_device() {
    echo -e "${CYAN}Detectando dispositivo...${NC}"
    
    # USB
    USB=$(adb devices 2>/dev/null | grep "device$" | head -1)
    if [ -n "$USB" ]; then
        DEVICE_ID=$(echo "$USB" | awk '{print $1}')
        echo -e "${GREEN}✓ USB: $DEVICE_ID${NC}"
        DEVICE="-s $DEVICE_ID"
        return 0
    fi
    
    # Rede
    NET=$(adb devices 2>/dev/null | grep ":" | grep "device" | head -1)
    if [ -n "$NET" ]; then
        DEVICE_ID=$(echo "$NET" | awk '{print $1}')
        echo -e "${GREEN}✓ Rede: $DEVICE_ID${NC}"
        DEVICE="-s $DEVICE_ID"
        return 0
    fi
    
    return 1
}

connect_network() {
    echo -e "${CYAN}Conectando a $1:5555...${NC}"
    adb disconnect "$1:5555" 2>/dev/null
    adb connect "$1:5555" && DEVICE="-s $1:5555"
}

device_info() {
    echo ""
    echo -e "${CYAN}═══ INFO ═══${NC}"
    adb $DEVICE shell getprop ro.product.model | xargs echo "  Modelo:"
    adb $DEVICE shell getprop ro.build.version.release | xargs echo "  Android:"
    adb $DEVICE shell getprop ro.product.cpu.abi | xargs echo "  CPU:"
}

check_app() {
    echo ""
    echo -e "${CYAN}═══ APP ═══${NC}"
    if adb $DEVICE shell pm list packages 2>/dev/null | grep -q "iplinks.player"; then
        echo -e "${GREEN}✓ App instalado${NC}"
        return 0
    else
        echo -e "${RED}✗ App NÃO instalado${NC}"
        return 1
    fi
}

start_stream() {
    echo ""
    echo -e "${CYAN}═══ INICIANDO STREAM ═══${NC}"
    adb $DEVICE shell am force-stop com.iplinks.player 2>/dev/null
    adb $DEVICE logcat -c 2>/dev/null
    adb $DEVICE shell am start -a android.intent.action.VIEW \
        -d "$STREAM_URL" \
        -t "application/x-mpegURL" \
        com.iplinks.player
    echo -e "${GREEN}✓ Stream enviado${NC}"
}

monitor() {
    echo ""
    echo -e "${CYAN}═══ MONITOR (Ctrl+C parar) ═══${NC}"
    echo ""
    adb $DEVICE logcat -v time PlayerActivity:* ExoPlayer:* MediaCodec:* 2>/dev/null | \
    grep --line-buffered -iE "error|warning|ready|playing|stall|recovery|resync"
}

# ========================================
# MAIN
# ========================================

banner

# Verificar ADB
if ! command -v adb &>/dev/null; then
    echo -e "${RED}ADB não encontrado!${NC}"
    exit 1
fi

# Argumento = IP da TV
if [ -n "$1" ]; then
    connect_network "$1"
else
    detect_device || {
        echo -e "${YELLOW}Nenhum dispositivo. Use: $0 <IP_TV>${NC}"
        exit 1
    }
fi

# Stream URL como segundo argumento
[ -n "$2" ] && STREAM_URL="$2"

device_info
check_app || exit 1
start_stream
monitor
