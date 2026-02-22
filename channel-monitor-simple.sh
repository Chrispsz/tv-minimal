#!/bin/bash
#
# ╔═══════════════════════════════════════════════════════════════════════════╗
# ║           🔴 CANAL RUIM - Versão Simplificada e Rápida                     ║
# ╚═══════════════════════════════════════════════════════════════════════════╝
#

PKG="com.iplinks.player"
LOG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/channel-monitor"
mkdir -p "$LOG_DIR"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="$LOG_DIR/canal_ruim_$TIMESTAMP.log"

# Cores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
CLEAR='\033[0m'

# Contadores
ERRORS=0
RECONNECTS=0
BUFFERS=0
NETWORK=0
DECODE=0

echo -e "${CYAN}╔═══════════════════════════════════════════════════════════════╗${CLEAR}"
echo -e "${CYAN}║           🔴 MONITOR DE CANAL RUIM - VERSÃO RÁPIDA            ║${CLEAR}"
echo -e "${CYAN}╚═══════════════════════════════════════════════════════════════╝${CLEAR}"
echo ""

# Verifica dispositivo
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}❌ Nenhum dispositivo conectado!${CLEAR}"
    exit 1
fi

# Verifica app
if ! adb shell pidof $PKG 2>/dev/null | grep -q "."; then
    echo -e "${RED}❌ App não está rodando! Abra o stream primeiro.${CLEAR}"
    exit 1
fi

echo -e "${GREEN}✅ Monitorando... Pressione Ctrl+C para parar${CLEAR}"
echo -e "${CYAN}📁 Log: $LOG_FILE${CLEAR}"
echo ""

# Limpa logcat
adb logcat -c 2>/dev/null

# Inicio
START=$(date +%s)

# Função de saída
trap 'echo ""; echo -e "${CYAN}═══════════════════════════════════════════════════════════════${CLEAR}"; DUR=$(( ($(date +%s) - START) / 60 )); echo -e "${YELLOW}📊 RELATÓRIO:${CLEAR}"; echo "   Duração: ${DUR}min | Erros: $ERRORS | Reconexões: $RECONNECTS | Buffer: $BUFFERS"; echo "   Rede: $NETWORK | Decode: $DECODE"; if [ "$ERRORS" -gt 10 ]; then echo -e "   Qualidade: ${RED}❌ RUIM${CLEAR}"; elif [ "$ERRORS" -gt 5 ]; then echo -e "   Qualidade: ${YELLOW}⚠️ REGULAR${CLEAR}"; else echo -e "   Qualidade: ${GREEN}✅ BOM${CLEAR}"; fi; exit 0' SIGINT SIGTERM

# Monitor simples
adb logcat -s "PlayerActivity:V" "ExoPlayer:V" "*:E" 2>/dev/null | while IFS= read -r line; do
    TS=$(date '+%H:%M:%S')
    
    # Detecta tipos de erro
    if echo "$line" | grep -qiE "error|exception|failed"; then
        ERRORS=$((ERRORS + 1))
        
        # Categoriza
        if echo "$line" | grep -qiE "connect|socket|timeout|network|unknownhost"; then
            NETWORK=$((NETWORK + 1))
            echo -e "${RED}[$TS] 🌐 ERRO REDE #$NETWORK${CLEAR}"
        elif echo "$line" | grep -qiE "decode|codec|omx|video"; then
            DECODE=$((DECODE + 1))
            echo -e "${RED}[$TS] 🎬 ERRO DECODE #$DECODE${CLEAR}"
        elif echo "$line" | grep -qiE "buffer"; then
            BUFFERS=$((BUFFERS + 1))
            echo -e "${YELLOW}[$TS] ⏳ BUFFER #$BUFFERS${CLEAR}"
        elif echo "$line" | grep -qiE "retry|reconnect|recover"; then
            RECONNECTS=$((RECONNECTS + 1))
            echo -e "${YELLOW}[$TS] 🔄 RECONNECT #$RECONNECTS${CLEAR}"
        else
            echo -e "${RED}[$TS] ❌ ERRO #$ERRORS${CLEAR}"
        fi
    fi
    
    # Salva no log
    echo "$line" >> "$LOG_FILE"
done
