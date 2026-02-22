#!/bin/bash

# ========================================
# Channel Monitor - Network Version
# Monitora TV Android via ADB over TCP/IP
# ========================================

# Configurações
TV_IP="${1:-}"           # IP da TV (obrigatório)
DURATION="${2:-300}"     # Duração em segundos (padrão: 5 min)
ADB_PORT="5555"

# Cores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# Arquivo de log
LOG_FILE="tv-monitor-$(date +%Y%m%d_%H%M%S).log"

echo -e "${CYAN}╔════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║     TV Channel Monitor (Network ADB)       ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════╝${NC}"
echo ""

# Verificar IP fornecido
if [ -z "$TV_IP" ]; then
    echo -e "${RED}Erro: IP da TV não fornecido!${NC}"
    echo ""
    echo "Uso: $0 <IP_DA_TV> [DURAÇÃO_SEGUNDOS]"
    echo ""
    echo "Exemplos:"
    echo "  $0 192.168.1.100           # Monitora por 5 minutos"
    echo "  $0 192.168.1.100 600       # Monitora por 10 minutos"
    echo "  $0 192.168.1.100 3600      # Monitora por 1 hora"
    echo ""
    echo -e "${YELLOW}Dica: Descubra o IP da TV em: Configurações > Rede > Info${NC}"
    exit 1
fi

# Verificar se ADB está instalado
if ! command -v adb &> /dev/null; then
    echo -e "${RED}Erro: ADB não encontrado!${NC}"
    echo ""
    echo "Instale com:"
    echo "  Ubuntu/Debian: sudo apt install android-tools-adb"
    echo "  macOS: brew install android-platform-tools"
    echo "  Windows: Baixe de https://developer.android.com/studio/releases/platform-tools"
    exit 1
fi

echo -e "${BLUE}📡 Conectando à TV em $TV_IP:$ADB_PORT...${NC}"

# Desconectar conexões anteriores
adb disconnect "$TV_IP:$ADB_PORT" 2>/dev/null

# Tentar conectar
if ! adb connect "$TV_IP:$ADB_PORT" 2>&1 | grep -q "connected"; then
    echo ""
    echo -e "${RED}❌ Falha ao conectar!${NC}"
    echo ""
    echo -e "${YELLOW}Verifique:${NC}"
    echo "  1. A TV e este computador estão na mesma rede?"
    echo "  2. ADB over network está ATIVADO na TV?"
    echo ""
    echo -e "${CYAN}Para ativar ADB na TV:${NC}"
    echo "  • Vá em: Configurações > Opções do Desenvolvedor"
    echo "  • Ative: 'Depuração USB' e 'Depuração por rede'"
    echo "  • Se não ver 'Opções do Desenvolvedor':"
    echo "    - Vá em: Configurações > Sobre"
    echo "    - Clique 7x em 'Build'"
    echo ""
    echo -e "${YELLOW}Se precisar ativar via USB primeiro:${NC}"
    echo "  1. Conecte USB à TV"
    echo "  2. Execute: adb tcpip 5555"
    echo "  3. Desconecte USB e use este script"
    exit 1
fi

echo -e "${GREEN}✓ Conectado com sucesso!${NC}"
echo ""

# Verificar se o app está rodando
CURRENT_APP=$(adb -s "$TV_IP:$ADB_PORT" shell "dumpsys window | grep mCurrentFocus" 2>/dev/null | head -1)
echo -e "${BLUE}App em foco:${NC} $CURRENT_APP"
echo ""

# Contadores
ERRORS=0
ANRS=0
OOMS=0
DISCONTINUITIES=0
RESYNCS=0
PLAYER_ERRORS=0

echo -e "${CYAN}══════════════════════════════════════════════${NC}"
echo -e "${CYAN}  Iniciando monitoramento por ${DURATION}s...${NC}"
echo -e "${CYAN}  Log: $LOG_FILE${NC}"
echo -e "${CYAN}  Pressione Ctrl+C para parar${NC}"
echo -e "${CYAN}══════════════════════════════════════════════${NC}"
echo ""

# Iniciar logcat em background
adb -s "$TV_IP:$ADB_PORT" logcat -v time *:V 2>/dev/null | tee "$LOG_FILE" | while IFS= read -r line; do
    timestamp=$(date +"%H:%M:%S")
    
    # Audio Discontinuity
    if echo "$line" | grep -q "UnexpectedDiscontinuityException"; then
        DISCONTINUITIES=$((DISCONTINUITIES + 1))
        echo -e "${YELLOW}[$timestamp] ⚠️  Audio Discontinuity #$DISCONTINUITIES${NC}"
        echo "$line" >> "${LOG_FILE}.events"
    fi
    
    # Audio Resync
    if echo "$line" | grep -q "Forcing audio resync"; then
        RESYNCS=$((RESYNCS + 1))
        echo -e "${GREEN}[$timestamp] 🔄 Audio Resync executado #$RESYNCS${NC}"
        echo "$line" >> "${LOG_FILE}.events"
    fi
    
    # Player Errors
    if echo "$line" | grep -q "Player error"; then
        PLAYER_ERRORS=$((PLAYER_ERRORS + 1))
        echo -e "${RED}[$timestamp] ❌ Player Error #$PLAYER_ERRORS${NC}"
        echo "$line" >> "${LOG_FILE}.events"
    fi
    
    # ANR
    if echo "$line" | grep -qi "ANR in"; then
        ANRS=$((ANRS + 1))
        echo -e "${RED}[$timestamp] 💀 ANR Detectado!${NC}"
        echo "$line" >> "${LOG_FILE}.events"
    fi
    
    # OOM
    if echo "$line" | grep -qi "OutOfMemory\|lowmemorykiller"; then
        OOMS=$((OOMS + 1))
        echo -e "${RED}[$timestamp] 🧠 OOM Detectado!${NC}"
        echo "$line" >> "${LOG_FILE}.events"
    fi
    
    # Crash
    if echo "$line" | grep -qi "FATAL EXCEPTION\|AndroidRuntime.*Error"; then
        ERRORS=$((ERRORS + 1))
        echo -e "${RED}[$timestamp] 💥 CRASH Detectado!${NC}"
        echo "$line" >> "${LOG_FILE}.events"
    fi
done &

LOGCAT_PID=$!

# Aguardar duração especificada
sleep "$DURATION"

# Parar logcat
kill $LOGCAT_PID 2>/dev/null

# Desconectar
adb disconnect "$TV_IP:$ADB_PORT" 2>/dev/null

echo ""
echo -e "${CYAN}══════════════════════════════════════════════${NC}"
echo -e "${CYAN}           RELATÓRIO FINAL                    ${NC}"
echo -e "${CYAN}══════════════════════════════════════════════${NC}"
echo ""
echo -e "📊 Estatísticas:"
echo -e "  • Duração: ${DURATION}s"
echo -e "  • Audio Discontinuities: ${YELLOW}${DISCONTINUITIES}${NC}"
echo -e "  • Audio Resyncs: ${GREEN}${RESYNCS}${NC}"
echo -e "  • Player Errors: ${RED}${PLAYER_ERRORS}${NC}"
echo -e "  • ANRs: ${RED}${ANRS}${NC}"
echo -e "  • OOMs: ${RED}${OOMS}${NC}"
echo -e "  • Crashes: ${RED}${ERRORS}${NC}"
echo ""

# Status final
if [ "$ERRORS" -eq 0 ] && [ "$ANRS" -eq 0 ] && [ "$OOMS" -eq 0 ]; then
    echo -e "${GREEN}✅ App estável durante o monitoramento!${NC}"
    if [ "$DISCONTINUITIES" -gt 0 ]; then
        echo -e "${YELLOW}⚠️  Discontinuidades de áudio detectadas (normal em streams HLS)${NC}"
        if [ "$RESYNCS" -gt 0 ]; then
            echo -e "${GREEN}✅ Mecanismo de resync executado automaticamente${NC}"
        fi
    fi
else
    echo -e "${RED}❌ Problemas detectados - revise os logs${NC}"
fi

echo ""
echo -e "📁 Logs salvos em:"
echo -e "   • $LOG_FILE (log completo)"
echo -e "   • ${LOG_FILE}.events (apenas eventos)"
echo ""
echo -e "${BLUE}Para ver eventos: cat ${LOG_FILE}.events${NC}"
echo -e "${BLUE}Para buscar específico: grep 'pattern' $LOG_FILE${NC}"
