#!/bin/bash
#
# ╔═══════════════════════════════════════════════════════════════════════════╗
# ║              🔍 CANAL RUIM - Monitor de Streams Problemáticos             ║
# ║                                                                           ║
# ║  Monitora um stream específico e coleta métricas de qualidade.            ║
# ║  Detecta: buffer, reconexões, erros, latência, drops.                     ║
# ╚═══════════════════════════════════════════════════════════════════════════╝
#

set -e

# ==================== CONFIGURAÇÃO ====================
PKG="com.iplinks.player"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${SCRIPT_DIR}/channel-monitor"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Arquivos de saída
LOG_FILE="$LOG_DIR/monitor_$TIMESTAMP.log"
REPORT_FILE="$LOG_DIR/report_$TIMESTAMP.md"
STATS_FILE="$LOG_DIR/stats_$TIMESTAMP.csv"

# Cores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
CLEAR='\033[0m'

# Contadores
ERROR_COUNT=0
RECONNECT_COUNT=0
BUFFER_COUNT=0
DECODE_ERROR_COUNT=0
NETWORK_ERROR_COUNT=0
BEHIND_LIVE_COUNT=0
TOTAL_ERRORS=0

# Timestamps
START_TIME=""
LAST_ERROR_TIME=""
FIRST_ERROR_TIME=""

# Métricas
declare -a ERROR_TIMESTAMPS
declare -a ERROR_TYPES
declare -a ERROR_MESSAGES

# ==================== INICIALIZAÇÃO ====================
mkdir -p "$LOG_DIR"

# ==================== FUNÇÕES ====================

show_banner() {
    echo -e "${PURPLE}╔═══════════════════════════════════════════════════════════════════════════╗${CLEAR}"
    echo -e "${PURPLE}║                                                                           ║${CLEAR}"
    echo -e "${PURPLE}║     ██████╗██╗  ██╗ █████╗ ██╗     ██████╗ █████╗ ███████╗██╗             ║${CLEAR}"
    echo -e "${PURPLE}║    ██╔════╝██║  ██║██╔══██╗██║    ██╔════╝██╔══██╗██╔════╝██║             ║${CLEAR}"
    echo -e "${PURPLE}║    ██║     ███████║███████║██║    ██║     ███████║███████╗██║             ║${CLEAR}"
    echo -e "${PURPLE}║    ██║     ██╔══██║██╔══██║██║    ██║     ██╔══██║╚════██║██║             ║${CLEAR}"
    echo -e "${PURPLE}║    ╚██████╗██║  ██║██║  ██║██║    ╚██████╗██║  ██║███████║██║             ║${CLEAR}"
    echo -e "${PURPLE}║     ╚═════╝╚═╝  ╚═╝╚═╝  ╚═╝╚═╝     ╚═════╝╚═╝  ╚═╝╚══════╝╚═╝             ║${CLEAR}"
    echo -e "${PURPLE}║                                                                           ║${CLEAR}"
    echo -e "${PURPLE}║          🔍 MONITOR DE STREAMS PROBLEMÁTICOS - CANAL RUIM 🔍             ║${CLEAR}"
    echo -e "${PURPLE}║                                                                           ║${CLEAR}"
    echo -e "${PURPLE}╚═══════════════════════════════════════════════════════════════════════════╝${CLEAR}"
    echo ""
}

check_device() {
    echo -e "${CYAN}[CHECK] Verificando dispositivo...${CLEAR}"
    
    if ! adb devices | grep -q "device$"; then
        echo -e "${RED}❌ Nenhum dispositivo conectado!${CLEAR}"
        exit 1
    fi
    
    DEVICE=$(adb devices | grep "device$" | awk '{print $1}')
    MODEL=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    
    echo -e "${GREEN}✅ Dispositivo: $MODEL ($DEVICE)${CLEAR}"
    echo ""
}

check_app() {
    echo -e "${CYAN}[CHECK] Verificando app...${CLEAR}"
    
    if ! adb shell pidof $PKG 2>/dev/null | grep -q "."; then
        echo -e "${RED}❌ App não está rodando!${CLEAR}"
        echo -e "${YELLOW}    Abra o app e inicie um stream antes de monitorar.${CLEAR}"
        exit 1
    fi
    
    PID=$(adb shell pidof $PKG 2>/dev/null | tr -d '\r')
    echo -e "${GREEN}✅ App rodando (PID: $PID)${CLEAR}"
    echo ""
}

init_csv() {
    echo "timestamp,error_type,error_message,reconnect_count,buffer_count" > "$STATS_FILE"
}

log_error() {
    local type="$1"
    local message="$2"
    local timestamp=$(date '+%H:%M:%S')
    
    TOTAL_ERRORS=$((TOTAL_ERRORS + 1))
    LAST_ERROR_TIME="$timestamp"
    
    if [ -z "$FIRST_ERROR_TIME" ]; then
        FIRST_ERROR_TIME="$timestamp"
    fi
    
    ERROR_TIMESTAMPS+=("$timestamp")
    ERROR_TYPES+=("$type")
    ERROR_MESSAGES+=("$message")
    
    # Log to CSV
    echo "$timestamp,$type,\"$message\",$RECONNECT_COUNT,$BUFFER_COUNT" >> "$STATS_FILE"
    
    # Log to file
    echo "[$timestamp] [$type] $message" >> "$LOG_FILE"
}

categorize_error() {
    local line="$1"
    local timestamp=$(date '+%H:%M:%S')
    
    # BehindLiveWindowException
    if echo "$line" | grep -q "BehindLiveWindowException"; then
        BEHIND_LIVE_COUNT=$((BEHIND_LIVE_COUNT + 1))
        echo -e "${YELLOW}[$timestamp] 📺 BEHIND_LIVE: Stream ficou atrás da janela live${CLEAR}"
        log_error "BEHIND_LIVE" "BehindLiveWindowException detected"
        return
    fi
    
    # Erros de rede
    if echo "$line" | grep -qE "ConnectException|UnknownHostException|SocketTimeoutException|SSLException"; then
        NETWORK_ERROR_COUNT=$((NETWORK_ERROR_COUNT + 1))
        ERROR_COUNT=$((ERROR_COUNT + 1))
        echo -e "${RED}[$timestamp] 🌐 ERRO_REDE: Falha de conexão${CLEAR}"
        log_error "NETWORK" "$(echo "$line" | grep -oE 'ConnectException|UnknownHostException|SocketTimeoutException|SSLException' | head -1)"
        return
    fi
    
    # Reconexão automática
    if echo "$line" | grep -qE "retry|reconnect|auto-recover"; then
        RECONNECT_COUNT=$((RECONNECT_COUNT + 1))
        echo -e "${YELLOW}[$timestamp] 🔄 RECONNECT: Tentativa de reconexão #$RECONNECT_COUNT${CLEAR}"
        log_error "RECONNECT" "Automatic retry triggered"
        return
    fi
    
    # Buffer
    if echo "$line" | grep -qE "BUFFERING|buffer.*underrun|Buffer.*empty"; then
        BUFFER_COUNT=$((BUFFER_COUNT + 1))
        echo -e "${YELLOW}[$timestamp] ⏳ BUFFER: Buffer underrun detectado${CLEAR}"
        log_error "BUFFER" "Buffer underrun"
        return
    fi
    
    # Erros de decode
    if echo "$line" | grep -qE "OMX.*ERROR|MediaCodec.*ERROR|Decoder.*failed|VIDEO_CODEC"; then
        DECODE_ERROR_COUNT=$((DECODE_ERROR_COUNT + 1))
        ERROR_COUNT=$((ERROR_COUNT + 1))
        echo -e "${RED}[$timestamp] 🎬 ERRO_DECODE: Falha no decoder${CLEAR}"
        log_error "DECODE" "$(echo "$line" | grep -oE 'OMX\.[a-zA-Z0-9._]+' | head -1)"
        return
    fi
    
    # Erros HTTP
    if echo "$line" | grep -qE "HTTP 4[0-9]{2}|HTTP 5[0-9]{2}|404|403|500|502|503"; then
        NETWORK_ERROR_COUNT=$((NETWORK_ERROR_COUNT + 1))
        ERROR_COUNT=$((ERROR_COUNT + 1))
        echo -e "${RED}[$timestamp] 🌐 ERRO_HTTP: Código de erro HTTP${CLEAR}"
        log_error "HTTP" "$(echo "$line" | grep -oE 'HTTP [0-9]{3}|[45][0-9]{2}' | head -1)"
        return
    fi
    
    # Erros genéricos do player
    if echo "$line" | grep -qE "PlayerActivity.*Error|ExoPlayer.*Error|playback.*error"; then
        ERROR_COUNT=$((ERROR_COUNT + 1))
        echo -e "${RED}[$timestamp] ❌ ERRO_PLAYER: Erro de playback${CLEAR}"
        log_error "PLAYER" "Playback error"
        return
    fi
}

get_current_stats() {
    local stats=""
    
    # Memória
    local mem=$(adb shell dumpsys meminfo $PKG 2>/dev/null | grep "TOTAL" | head -1 | awk '{print $2}')
    stats="RAM: ${mem:-N/A}KB"
    
    # CPU
    local cpu=$(adb shell top -n 1 -o %CPU 2>/dev/null | grep "$PKG" | awk '{print $9}' | head -1)
    stats="$stats | CPU: ${cpu:-N/A}%"
    
    # Temperatura
    local temp=$(adb shell dumpsys battery 2>/dev/null | grep "temperature" | awk -F= '{print $2}')
    if [ -n "$temp" ] && [ "$temp" -gt 0 ] 2>/dev/null; then
        local temp_c=$((temp / 10))
        stats="$stats | Temp: ${temp_c}°C"
    fi
    
    echo "$stats"
}

show_live_stats() {
    local duration=$1
    local duration_sec=$((duration / 60))
    local duration_min=$((duration_sec / 60))
    local duration_hr=$((duration_min / 60))
    
    local stats=$(get_current_stats)
    
    echo -e "${CYAN}┌─────────────────────────────────────────────────────────────┐${CLEAR}"
    echo -e "${CYAN}│ 📊 ESTATÍSTICAS EM TEMPO REAL                              │${CLEAR}"
    echo -e "${CYAN}├─────────────────────────────────────────────────────────────┤${CLEAR}"
    echo -e "${CYAN}│ Duração: ${duration_min}min │ Erros: ${TOTAL_ERRORS} │ Reconexões: ${RECONNECT_COUNT}        │${CLEAR}"
    echo -e "${CYAN}│ Buffer: ${BUFFER_COUNT} │ Rede: ${NETWORK_ERROR_COUNT} │ Decode: ${DECODE_ERROR_COUNT}               │${CLEAR}"
    echo -e "${CYAN}│ $stats │${CLEAR}"
    echo -e "${CYAN}└─────────────────────────────────────────────────────────────┘${CLEAR}"
}

generate_report() {
    local duration=$1
    local end_time=$(date '+%H:%M:%S')
    
    # Calcula duração formatada
    local duration_sec=$((duration / 60))
    local duration_min=$((duration_sec / 60))
    
    # Calcula taxa de erros por minuto
    local error_rate="0"
    if [ "$duration_min" -gt 0 ]; then
        error_rate=$(echo "scale=2; $TOTAL_ERRORS / $duration_min" | bc 2>/dev/null || echo "0")
    fi
    
    # Determina qualidade do canal
    local quality="✅ BOM"
    local quality_color="$GREEN"
    
    if [ "$TOTAL_ERRORS" -gt 20 ] || [ "$RECONNECT_COUNT" -gt 5 ]; then
        quality="❌ RUIM"
        quality_color="$RED"
    elif [ "$TOTAL_ERRORS" -gt 10 ] || [ "$RECONNECT_COUNT" -gt 2 ]; then
        quality="⚠️ REGULAR"
        quality_color="$YELLOW"
    fi
    
    echo ""
    echo -e "${PURPLE}╔═══════════════════════════════════════════════════════════════╗${CLEAR}"
    echo -e "${PURPLE}║                    📋 RELATÓRIO FINAL                         ║${CLEAR}"
    echo -e "${PURPLE}╚═══════════════════════════════════════════════════════════════╝${CLEAR}"
    echo ""
    
    cat > "$REPORT_FILE" << EOF
# 📊 Relatório de Monitoramento - Canal

**Data:** $(date)
**Duração:** ${duration_min} minutos
**Horário:** $START_TIME - $end_time

---

## 📈 Resumo

| Métrica | Valor |
|---------|-------|
| **Qualidade** | $quality |
| **Total de Erros** | $TOTAL_ERRORS |
| **Erros/Minuto** | ${error_rate}/min |
| **Reconexões** | $RECONNECT_COUNT |
| **Buffer Underruns** | $BUFFER_COUNT |

---

## 📊 Detalhamento de Erros

| Tipo | Quantidade |
|------|------------|
| 🌐 Rede | $NETWORK_ERROR_COUNT |
| 🎬 Decode | $DECODE_ERROR_COUNT |
| 📺 Behind Live | $BEHIND_LIVE_COUNT |
| 🔄 Reconexões | $RECONNECT_COUNT |
| ⏳ Buffer | $BUFFER_COUNT |

---

## 🕐 Timeline de Erros

EOF

    # Adiciona últimos 20 erros
    echo '```' >> "$REPORT_FILE"
    local count=0
    for i in "${!ERROR_TIMESTAMPS[@]}"; do
        if [ $count -lt 20 ]; then
            echo "[${ERROR_TIMESTAMPS[$i]}] ${ERROR_TYPES[$i]}: ${ERROR_MESSAGES[$i]}" >> "$REPORT_FILE"
            count=$((count + 1))
        fi
    done
    echo '```' >> "$REPORT_FILE"
    
    # Conclusão
    cat >> "$REPORT_FILE" << EOF

---

## 🎯 Conclusão

$quality

EOF

    if [ "$quality" = "❌ RUIM" ]; then
        echo "**Recomendação:** Este canal apresenta problemas frequentes. Considere:" >> "$REPORT_FILE"
        echo "- Verificar estabilidade do servidor" >> "$REPORT_FILE"
        echo "- Testar em outro momento" >> "$REPORT_FILE"
        echo "- Reportar ao provedor" >> "$REPORT_FILE"
    elif [ "$quality" = "⚠️ REGULAR" ]; then
        echo "**Recomendação:** Canal com oscilações. Aceitável para uso eventual." >> "$REPORT_FILE"
    else
        echo "**Recomendação:** Canal estável, bom para uso regular." >> "$REPORT_FILE"
    fi
    
    # Mostra relatório na tela
    echo -e "┌─────────────────────────────────────────────────────────────┐"
    echo -e "│ 📈 MÉTRICAS                                                 │"
    echo -e "├─────────────────────────────────────────────────────────────┤"
    echo -e "│ Duração:          ${duration_min} minutos                              "
    echo -e "│ Total Erros:      ${TOTAL_ERRORS}                                      "
    echo -e "│ Erros/Minuto:     ${error_rate}/min                               "
    echo -e "│ Reconexões:       ${RECONNECT_COUNT}                                      "
    echo -e "│ Buffer Issues:    ${BUFFER_COUNT}                                      "
    echo -e "├─────────────────────────────────────────────────────────────┤"
    echo -e "│ QUALIDADE: ${quality_color}${quality}${CLEAR}                                        "
    echo -e "└─────────────────────────────────────────────────────────────┘"
    echo ""
    echo -e "${CYAN}[INFO] Relatório salvo em: $REPORT_FILE${CLEAR}"
    echo -e "${CYAN}[INFO] Logs salvos em: $LOG_FILE${CLEAR}"
    echo -e "${CYAN}[INFO] Estatísticas em: $STATS_FILE${CLEAR}"
}

# ==================== EXECUÇÃO PRINCIPAL ====================

show_banner
check_device
check_app

START_TIME=$(date '+%H:%M:%S')
START_TIMESTAMP=$(date +%s)

echo -e "${CYAN}[INFO] Iniciando monitoramento...${CLEAR}"
echo -e "${CYAN}[INFO] Logs em: $LOG_FILE${CLEAR}"
echo -e "${YELLOW}[INFO] Pressione Ctrl+C para parar e gerar relatório${CLEAR}"
echo ""

init_csv

# Limpa logcat anterior
adb logcat -c 2>/dev/null

# Contador de tempo
ELAPSED=0

# Trap para sair limpo
trap 'generate_report $ELAPSED; exit 0' SIGINT SIGTERM

# Loop principal de monitoramento
while true; do
    # Lê logcat em tempo real
    adb logcat -s "PlayerActivity:V" "ExoPlayer:V" "HlsMediaSource:V" "MediaCodec:V" "OMX:V" "*:E" 2>/dev/null | while IFS= read -r line; do
        # Processa cada linha
        categorize_error "$line"
        
        # Atualiza stats a cada 10 erros
        if [ $((TOTAL_ERRORS % 10)) -eq 0 ] && [ "$TOTAL_ERRORS" -gt 0 ]; then
            show_live_stats $ELAPSED
        fi
        
        # Log tudo
        echo "$line" >> "$LOG_FILE"
    done &
    
    LOGCAT_PID=$!
    
    # Loop de stats
    while kill -0 $LOGCAT_PID 2>/dev/null; do
        ELAPSED=$(( ($(date +%s) - START_TIMESTAMP) * 60 ))
        
        # Mostra stats a cada 30 segundos
        sleep 30
        show_live_stats $ELAPSED
    done
    
    wait $LOGCAT_PID
done
