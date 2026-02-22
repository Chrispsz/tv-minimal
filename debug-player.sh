#!/bin/bash

# ========================================
# Debug Player Script
# Testa o funcionamento do IPLINKS Player
# ========================================

TV_IP="${1:-}"
STREAM_URL="${2:-}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}╔════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║        IPLINKS Player Debug Tool           ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════╝${NC}"

# Verificar ADB
if ! command -v adb &> /dev/null; then
    echo -e "${RED}ADB não encontrado!${NC}"
    exit 1
fi

# Se IP fornecido, conectar
if [ -n "$TV_IP" ]; then
    echo -e "${CYAN}Conectando a $TV_IP:5555...${NC}"
    adb disconnect "$TV_IP:5555" 2>/dev/null
    adb connect "$TV_IP:5555"
    DEVICE="-s $TV_IP:5555"
else
    DEVICE=""
fi

echo ""
echo -e "${YELLOW}Comandos disponíveis:${NC}"
echo ""
echo "  1) Instalar APK"
echo "  2) Iniciar stream (URL necessária)"
echo "  3) Monitorar logs"
echo "  4) Verificar status do player"
echo "  5) Testar intents"
echo "  6) Limpar dados do app"
echo "  7) Info do dispositivo"
echo ""
echo -e "${YELLOW}Uso:${NC}"
echo "  $0 <IP_TV> <STREAM_URL>"
echo "  $0 192.168.1.100 https://exemplo.com/stream.m3u8"
echo ""

# Se tiver URL, iniciar stream
if [ -n "$STREAM_URL" ]; then
    echo -e "${GREEN}Iniciando stream...${NC}"
    adb $DEVICE shell am start -a android.intent.action.VIEW \
        -d "iplinks://play?url=$STREAM_URL" \
        com.iplinks.player
    
    echo ""
    echo -e "${CYAN}Monitorando logs (Ctrl+C para parar)...${NC}"
    adb $DEVICE logcat -s PlayerActivity:* ExoPlayer:* MediaCodec:* -v time
fi
