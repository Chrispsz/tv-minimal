#!/bin/bash

# ========================================
# IPLINKS Player - Teste Completo com Logs
# ========================================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

STREAM_URL="http://cdn4k.club/live/j5z6h3pb/mv67p015c/6589.m3u8"
DEVICE=""
LOG_FILE="player-test-$(date +%Y%m%d_%H%M%S).log"
DURATION=${1:-60}  # Segundos (padrão 60s)

# Detectar dispositivo
detect() {
    USB=$(adb devices 2>/dev/null | grep "device$" | head -1)
    if [ -n "$USB" ]; then
        DEVICE_ID=$(echo "$USB" | awk '{print $1}')
        echo -e "${GREEN}✓ USB: $DEVICE_ID${NC}"
        DEVICE="-s $DEVICE_ID"
        return 0
    fi
    
    NET=$(adb devices 2>/dev/null | grep ":" | grep "device" | head -1)
    if [ -n "$NET" ]; then
        DEVICE_ID=$(echo "$NET" | awk '{print $1}')
        echo -e "${GREEN}✓ Rede: $DEVICE_ID${NC}"
        DEVICE="-s $DEVICE_ID"
        return 0
    fi
    
    return 1
}

echo -e "${CYAN}╔════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║    IPLINKS Player - Teste com Logs         ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════╝${NC}"
echo ""

# Detectar
detect || {
    echo -e "${RED}Nenhum dispositivo!${NC}"
    exit 1
}

[ -n "$2" ] && STREAM_URL="$2"

echo ""
echo -e "${CYAN}═══ CONFIG ═══${NC}"
echo "  Duração: ${DURATION}s"
echo "  Stream: ${STREAM_URL:0:50}..."
echo "  Log: $LOG_FILE"
echo ""

# Iniciar stream
echo -e "${CYAN}═══ INICIANDO ═══${NC}"
adb $DEVICE shell am force-stop com.iplinks.player 2>/dev/null
adb $DEVICE logcat -c 2>/dev/null
adb $DEVICE shell am start -a android.intent.action.VIEW -d "$STREAM_URL" -t "application/x-mpegURL" com.iplinks.player
echo -e "${GREEN}✓ Stream iniciado${NC}"
echo ""

# Monitorar
echo -e "${CYAN}═══ MONITORANDO (${DURATION}s) ═══${NC}"
echo ""

START=$(date +%s)

# Capturar logs completos
adb $DEVICE logcat -v time 2>/dev/null | while read -r line; do
    NOW=$(date +%s)
    ELAPSED=$((NOW - START))
    
    [ $ELAPSED -ge $DURATION ] && break
    
    # Salvar tudo
    echo "$line" >> "$LOG_FILE"
    
    # Mostrar apenas relevante
    case "$line" in
        *PlayerActivity*)
            echo -e "${GREEN}[$ELAPSEDs] $line${NC}"
            ;;
        *ExoPlayer*|*AudioSink*|*MediaCodec*)
            if echo "$line" | grep -qiE "error|warning|stall|buffer|network|timeout"; then
                echo -e "${YELLOW}[$ELAPSEDs] $line${NC}"
            fi
            ;;
        *UnexpectedDiscontinuity*)
            echo -e "${RED}[$ELAPSEDs] AUDIO DESYNC${NC}"
            ;;
        *BehindLiveWindow*)
            echo -e "${RED}[$ELAPSEDs] LIVE DELAY${NC}"
            ;;
    esac
done

echo ""
echo -e "${CYAN}═══ ANÁLISE ═══${NC}"
echo ""

# Analisar logs
ERRORS=$(grep -c "Error\|ERROR" "$LOG_FILE" 2>/dev/null || echo 0)
WARNINGS=$(grep -c "Warning\|WARNING" "$LOG_FILE" 2>/dev/null || echo 0)
DISCONTINUITY=$(grep -c "UnexpectedDiscontinuity" "$LOG_FILE" 2>/dev/null || echo 0)
BEHIND_LIVE=$(grep -c "BehindLiveWindow" "$LOG_FILE" 2>/dev/null || echo 0)
STALLS=$(grep -c "stall\|Stall" "$LOG_FILE" 2>/dev/null || echo 0)
RECOVERIES=$(grep -c "recovery\|resync\|restart" "$LOG_FILE" 2>/dev/null || echo 0)
NETWORK=$(grep -c "network\|Network\|timeout\|Timeout" "$LOG_FILE" 2>/dev/null || echo 0)

echo "  Erros: $ERRORS"
echo "  Warnings: $WARNINGS"
echo "  Audio Discontinuity: $DISCONTINUITY"
echo "  Behind Live Window: $BEHIND_LIVE"
echo "  Stalls: $STALLS"
echo "  Recuperações: $RECOVERIES"
echo "  Network issues: $NETWORK"
echo ""

if [ $ERRORS -eq 0 ] && [ $DISCONTINUITY -le 5 ]; then
    echo -e "${GREEN}✅ PLAYER ESTÁVEL${NC}"
else
    echo -e "${YELLOW}⚠️ PROBLEMAS DETECTADOS${NC}"
fi

echo ""
echo -e "${CYAN}Log completo: $LOG_FILE${NC}"
echo -e "${CYAN}Filtrar PlayerActivity: grep PlayerActivity $LOG_FILE${NC}"
