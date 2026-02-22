#!/bin/bash

# ========================================
# Quick Stream Test
# ========================================

TV_IP="${1}"
STREAM_URL="http://209.131.122.136/live/j5z6h3pb/mv67p015c/6589.m3u8?token=WFhmeDdCR0p4eFMwaGdJ"

CYAN='\033[0;36m'
GREEN='\033[0;32m'
NC='\033[0m'

if [ -z "$TV_IP" ]; then
    echo "Uso: $0 <IP_DA_TV>"
    echo "Exemplo: $0 192.168.1.100"
    exit 1
fi

echo -e "${CYAN}Conectando a $TV_IP...${NC}"
adb connect "$TV_IP:5555"

echo -e "${GREEN}Iniciando stream...${NC}"
adb -s "$TV_IP:5555" shell am start -a android.intent.action.VIEW \
    -d "iplinks://play?url=$STREAM_URL" \
    com.iplinks.player

echo -e "${CYAN}Logs (Ctrl+C para parar):${NC}"
adb -s "$TV_IP:5555" logcat -s PlayerActivity:* ExoPlayer:* MediaCodec:* -v time | \
  grep --line-buffered -E "PlayerActivity|Audio|Network|Error|STALL"
