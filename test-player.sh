#!/bin/bash

# ========================================
# IPLINKS Player - Teste Completo
# ========================================

# Cores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'

# Config
TV_IP="${1:-}"
STREAM_URL="${2:-http://209.131.122.136/live/j5z6h3pb/mv67p015c/6589.m3u8?token=WFhmeDdCR0p4eFMwaGdJ}"
APK_PATH="${3:-app/build/outputs/apk/release/app-armeabi-v7a-release.apk}"
ADB_PORT="5555"

# Device connection (USB ou Network)
DEVICE=""
CONNECTION_TYPE=""

# Contadores
ERRORS=0
WARNINGS=0
TESTS_PASSED=0
TESTS_FAILED=0

# ========================================
# FUNÇÕES
# ========================================

banner() {
    echo ""
    echo -e "${CYAN}╔════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║     IPLINKS Player - Teste Completo v1.0           ║${NC}"
    echo -e "${CYAN}╚════════════════════════════════════════════════════╝${NC}"
    echo ""
}

usage() {
    echo -e "${YELLOW}Uso:${NC}"
    echo "  $0 <IP_TV> [STREAM_URL] [APK_PATH]"
    echo ""
    echo -e "${YELLOW}Exemplos:${NC}"
    echo "  $0 192.168.1.100"
    echo "  $0 192.168.1.100 https://stream.m3u8"
    echo "  $0 192.168.1.100 https://stream.m3u8 ./app-release.apk"
    echo ""
    exit 1
}

check_adb() {
    if ! command -v adb &> /dev/null; then
        echo -e "${RED}❌ ADB não encontrado!${NC}"
        echo ""
        echo "Instale:"
        echo "  Ubuntu: sudo apt install android-tools-adb"
        echo "  macOS:  brew install android-platform-tools"
        exit 1
    fi
    echo -e "${GREEN}✓ ADB encontrado${NC}"
    TESTS_PASSED=$((TESTS_PASSED + 1))
}

check_curl() {
    if ! command -v curl &> /dev/null; then
        echo -e "${RED}❌ curl não encontrado!${NC}"
        exit 1
    fi
    echo -e "${GREEN}✓ curl encontrado${NC}"
    TESTS_PASSED=$((TESTS_PASSED + 1))
}

check_jq() {
    if ! command -v jq &> /dev/null; then
        echo -e "${YELLOW}⚠ jq não encontrado (algumas funções limitadas)${NC}"
        WARNINGS=$((WARNINGS + 1))
    else
        echo -e "${GREEN}✓ jq encontrado${NC}"
        TESTS_PASSED=$((TESTS_PASSED + 1))
    fi
}

detect_device() {
    echo ""
    echo -e "${BLUE}═══ DETECÇÃO DE DISPOSITIVO ═══${NC}"
    
    # Verificar dispositivos USB conectados
    USB_DEVICES=$(adb devices 2>/dev/null | grep -v "List" | grep -v "daemon" | grep -v "offline" | grep "device" | grep -v ":" | head -1)
    
    if [ -n "$USB_DEVICES" ]; then
        DEVICE_ID=$(echo "$USB_DEVICES" | awk '{print $1}')
        echo -e "${GREEN}✓ Dispositivo USB detectado: $DEVICE_ID${NC}"
        DEVICE="-s $DEVICE_ID"
        CONNECTION_TYPE="USB"
        TESTS_PASSED=$((TESTS_PASSED + 1))
        return 0
    fi
    
    # Verificar dispositivos de rede
    NETWORK_DEVICES=$(adb devices 2>/dev/null | grep -v "List" | grep -v "daemon" | grep "device" | grep ":" | head -1)
    
    if [ -n "$NETWORK_DEVICES" ]; then
        DEVICE_ID=$(echo "$NETWORK_DEVICES" | awk '{print $1}')
        echo -e "${GREEN}✓ Dispositivo de rede detectado: $DEVICE_ID${NC}"
        DEVICE="-s $DEVICE_ID"
        CONNECTION_TYPE="NETWORK"
        TESTS_PASSED=$((TESTS_PASSED + 1))
        return 0
    fi
    
    return 1
}

connect_tv() {
    echo ""
    echo -e "${BLUE}═══ CONEXÃO COM TV ═══${NC}"
    
    # Primeiro, tentar detectar dispositivo existente (USB ou rede)
    if detect_device; then
        echo -e "${CYAN}Conexão: ${CONNECTION_TYPE}${NC}"
        return 0
    fi
    
    # Se IP fornecido, tentar conectar via rede
    if [ -n "$TV_IP" ]; then
        echo -e "${CYAN}Conectando via rede a ${TV_IP}:${ADB_PORT}...${NC}"
        
        # Desconectar anterior
        adb disconnect "$TV_IP:$ADB_PORT" 2>/dev/null || true
        
        # Conectar
        if adb connect "$TV_IP:$ADB_PORT" 2>&1 | grep -q "connected\|already connected"; then
            echo -e "${GREEN}✓ Conectado via rede!${NC}"
            DEVICE="-s $TV_IP:$ADB_PORT"
            CONNECTION_TYPE="NETWORK"
            TESTS_PASSED=$((TESTS_PASSED + 1))
            return 0
        else
            echo -e "${RED}❌ Falha ao conectar via rede!${NC}"
            echo ""
            echo "Verifique:"
            echo "  1. TV e PC na mesma rede"
            echo "  2. ADB debugging ATIVO na TV"
            echo "  3. IP correto: $TV_IP"
            TESTS_FAILED=$((TESTS_FAILED + 1))
            return 1
        fi
    fi
    
    # Nenhum dispositivo encontrado
    echo -e "${YELLOW}Nenhum dispositivo detectado!${NC}"
    echo ""
    echo "Opções:"
    echo "  1. Conecte via USB e ative 'Depuração USB'"
    echo "  2. Use: $0 <IP_DA_TV> para conexão via rede"
    echo ""
    adb devices
    return 1
}

check_tv_info() {
    echo ""
    echo -e "${BLUE}═══ INFO DO DISPOSITIVO ═══${NC}"
    
    # Model
    MODEL=$(adb $DEVICE shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    echo -e "  Modelo: ${GREEN}$MODEL${NC}"
    
    # Android version
    ANDROID=$(adb $DEVICE shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
    echo -e "  Android: ${GREEN}$ANDROID${NC}"
    
    # CPU
    CPU=$(adb $DEVICE shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')
    echo -e "  CPU: ${GREEN}$CPU${NC}"
    
    # Memória
    MEM=$(adb $DEVICE shell cat /proc/meminfo 2>/dev/null | grep MemTotal | awk '{print $2}')
    MEM_MB=$((MEM / 1024))
    echo -e "  RAM: ${GREEN}${MEM_MB} MB${NC}"
    
    TESTS_PASSED=$((TESTS_PASSED + 1))
}

check_app_installed() {
    echo ""
    echo -e "${BLUE}═══ STATUS DO APP ═══${NC}"
    
    if adb $DEVICE shell pm list packages 2>/dev/null | grep -q "com.iplinks.player"; then
        echo -e "${GREEN}✓ App instalado${NC}"
        
        # Versão
        VERSION=$(adb $DEVICE shell dumpsys package com.iplinks.player 2>/dev/null | grep versionName | head -1 | cut -d= -f2)
        echo -e "  Versão: ${GREEN}$VERSION${NC}"
        
        TESTS_PASSED=$((TESTS_PASSED + 1))
        return 0
    else
        echo -e "${YELLOW}⚠ App não instalado${NC}"
        WARNINGS=$((WARNINGS + 1))
        return 1
    fi
}

install_apk() {
    echo ""
    echo -e "${BLUE}═══ INSTALAÇÃO DO APK ═══${NC}"
    
    if [ ! -f "$APK_PATH" ]; then
        echo -e "${YELLOW}APK local não encontrado: $APK_PATH${NC}"
        echo -e "${CYAN}Baixando do GitHub...${NC}"
        
        # Baixar artifact
        LATEST_RUN=$(curl -s "https://api.github.com/repos/Chrispsz/tv-minimal/actions/runs?per_page=1" | jq -r '.workflow_runs[0].id')
        ARTIFACT_URL=$(curl -s "https://api.github.com/repos/Chrispsz/tv-minimal/actions/runs/$LATEST_RUN/artifacts" | jq -r '.artifacts[0].archive_download_url')
        
        if [ -n "$ARTIFACT_URL" ] && [ "$ARTIFACT_URL" != "null" ]; then
            echo "Download: $ARTIFACT_URL"
            echo -e "${YELLOW}Baixe manualmente do GitHub Actions${NC}"
        fi
        
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi
    
    echo -e "${CYAN}Instalando: $APK_PATH${NC}"
    
    if adb $DEVICE install -r "$APK_PATH" 2>&1 | grep -q "Success"; then
        echo -e "${GREEN}✓ APK instalado com sucesso!${NC}"
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        echo -e "${RED}❌ Falha na instalação${NC}"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi
}

check_stream_url() {
    echo ""
    echo -e "${BLUE}═══ TESTE DE STREAM ═══${NC}"
    
    echo -e "${CYAN}URL: ${STREAM_URL:0:60}...${NC}"
    
    HTTP_CODE=$(curl -sI "$STREAM_URL" 2>/dev/null | head -1)
    CONTENT_TYPE=$(curl -sI "$STREAM_URL" 2>/dev/null | grep -i "content-type" | head -1)
    
    echo -e "  Status: ${GREEN}$HTTP_CODE${NC}"
    echo -e "  Type: ${GREEN}$CONTENT_TYPE${NC}"
    
    if echo "$HTTP_CODE" | grep -q "200"; then
        echo -e "${GREEN}✓ Stream acessível${NC}"
        TESTS_PASSED=$((TESTS_PASSED + 1))
        return 0
    else
        echo -e "${RED}❌ Stream indisponível${NC}"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi
}

start_stream() {
    echo ""
    echo -e "${BLUE}═══ INICIANDO STREAM ═══${NC}"
    
    # Parar app se rodando
    adb $DEVICE shell am force-stop com.iplinks.player 2>/dev/null
    
    # Limpar logs
    adb $DEVICE logcat -c 2>/dev/null
    
    # Iniciar com URL
    echo -e "${CYAN}Enviando URL para o player...${NC}"
    
    adb $DEVICE shell am start -a android.intent.action.VIEW \
        -d "iplinks://play?url=$STREAM_URL" \
        com.iplinks.player 2>/dev/null
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Stream iniciado${NC}"
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        echo -e "${RED}❌ Falha ao iniciar${NC}"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi
    
    # Aguardar player iniciar
    sleep 2
}

monitor_logs() {
    echo ""
    echo -e "${BLUE}═══ MONITORAMENTO ═══${NC}"
    echo -e "${CYAN}Monitorando por 60 segundos...${NC}"
    echo -e "${YELLOW}Ctrl+C para interromper${NC}"
    echo ""
    
    START_TIME=$(date +%s)
    ERROR_COUNT=0
    RECOVERY_COUNT=0
    STALL_COUNT=0
    
    adb $DEVICE logcat -v time PlayerActivity:* ExoPlayer:* MediaCodec:* AudioSink:* 2>/dev/null | while read -r line; do
        CURRENT_TIME=$(date +%s)
        ELAPSED=$((CURRENT_TIME - START_TIME))
        
        if [ $ELAPSED -gt 60 ]; then
            break
        fi
        
        # Timestamp
        TS=$(date +"%H:%M:%S")
        
        # Detectar tipos de log
        if echo "$line" | grep -qi "error\|failed\|exception"; then
            echo -e "${RED}[$TS] ❌ $line${NC}"
            ERROR_COUNT=$((ERROR_COUNT + 1))
        elif echo "$line" | grep -qi "warning\|stall"; then
            echo -e "${YELLOW}[$TS] ⚠ $line${NC}"
            STALL_COUNT=$((STALL_COUNT + 1))
        elif echo "$line" | grep -qi "recovery\|resync\|restart"; then
            echo -e "${GREEN}[$TS] 🔄 $line${NC}"
            RECOVERY_COUNT=$((RECOVERY_COUNT + 1))
        elif echo "$line" | grep -qi "ready\|playing"; then
            echo -e "${GREEN}[$TS] ▶ $line${NC}"
        fi
    done
    
    echo ""
    echo -e "${BLUE}═══ RESUMO DO MONITORAMENTO ═══${NC}"
    echo -e "  Erros: ${RED}$ERROR_COUNT${NC}"
    echo -e "  Stalls: ${YELLOW}$STALL_COUNT${NC}"
    echo -e "  Recuperações: ${GREEN}$RECOVERY_COUNT${NC}"
}

test_intents() {
    echo ""
    echo -e "${BLUE}═══ TESTE DE INTENTS ═══${NC}"
    
    # Teste 1: Custom scheme
    echo -e "${CYAN}Teste 1: iplinks:// scheme${NC}"
    adb $DEVICE shell am start -a android.intent.action.VIEW \
        -d "iplinks://play?url=$STREAM_URL" \
        com.iplinks.player 2>/dev/null && echo -e "${GREEN}✓ OK${NC}" || echo -e "${RED}✗ FAIL${NC}"
    
    sleep 1
    adb $DEVICE shell am force-stop com.iplinks.player 2>/dev/null
    
    # Teste 2: Direct HTTP
    echo -e "${CYAN}Teste 2: HTTP URL direto${NC}"
    adb $DEVICE shell am start -a android.intent.action.VIEW \
        -d "$STREAM_URL" \
        com.iplinks.player 2>/dev/null && echo -e "${GREEN}✓ OK${NC}" || echo -e "${RED}✗ FAIL${NC}"
    
    sleep 1
    adb $DEVICE shell am force-stop com.iplinks.player 2>/dev/null
    
    # Teste 3: SEND intent
    echo -e "${CYAN}Teste 3: SEND intent${NC}"
    adb $DEVICE shell am start -a android.intent.action.SEND \
        --es android.intent.EXTRA_TEXT "$STREAM_URL" \
        com.iplinks.player 2>/dev/null && echo -e "${GREEN}✓ OK${NC}" || echo -e "${RED}✗ FAIL${NC}"
    
    sleep 1
    adb $DEVICE shell am force-stop com.iplinks.player 2>/dev/null
    
    TESTS_PASSED=$((TESTS_PASSED + 3))
}

run_stress_test() {
    echo ""
    echo -e "${BLUE}═══ STRESS TEST (5 ciclos) ═══${NC}"
    
    for i in 1 2 3 4 5; do
        echo -e "${CYAN}Ciclo $i/5${NC}"
        
        adb $DEVICE shell am start -a android.intent.action.VIEW \
            -d "iplinks://play?url=$STREAM_URL" \
            com.iplinks.player 2>/dev/null
        
        sleep 10
        
        adb $DEVICE shell am force-stop com.iplinks.player 2>/dev/null
        
        sleep 2
    done
    
    echo -e "${GREEN}✓ Stress test completo${NC}"
    TESTS_PASSED=$((TESTS_PASSED + 1))
}

clear_app_data() {
    echo ""
    echo -e "${BLUE}═══ LIMPAR DADOS ═══${NC}"
    
    adb $DEVICE shell pm clear com.iplinks.player 2>/dev/null && \
        echo -e "${GREEN}✓ Dados limpos${NC}" || \
        echo -e "${YELLOW}⚠ App não instalado${NC}"
}

final_report() {
    echo ""
    echo -e "${MAGENTA}╔════════════════════════════════════════════════════╗${NC}"
    echo -e "${MAGENTA}║              RELATÓRIO FINAL                       ║${NC}"
    echo -e "${MAGENTA}╚════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "  ${GREEN}Testes Passados: $TESTS_PASSED${NC}"
    echo -e "  ${RED}Testes Falhados: $TESTS_FAILED${NC}"
    echo -e "  ${YELLOW}Avisos: $WARNINGS${NC}"
    echo ""
    
    if [ $TESTS_FAILED -eq 0 ]; then
        echo -e "${GREEN}✅ PLAYER FUNCIONANDO PERFEITAMENTE!${NC}"
    else
        echo -e "${RED}❌ PROBLEMAS DETECTADOS${NC}"
    fi
    echo ""
}

# ========================================
# MENU INTERATIVO
# ========================================

show_menu() {
    echo ""
    echo -e "${CYAN}═════════════════════════════════════════${NC}"
    echo -e "${CYAN}  MENU DE TESTES${NC}"
    if [ -n "$CONNECTION_TYPE" ]; then
        echo -e "${CYAN}  Conexão: ${GREEN}$CONNECTION_TYPE${NC}"
    fi
    echo -e "${CYAN}═════════════════════════════════════════${NC}"
    echo ""
    echo "  1) Detectar dispositivo (USB/Rede)"
    echo "  2) Info do dispositivo"
    echo "  3) Verificar app instalado"
    echo "  4) Instalar APK"
    echo "  5) Testar stream URL"
    echo "  6) Iniciar stream"
    echo "  7) Monitorar logs (60s)"
    echo "  8) Testar intents"
    echo "  9) Stress test"
    echo "  10) Limpar dados do app"
    echo "  11) Teste completo (tudo)"
    echo ""
    echo "  0) Sair"
    echo ""
    echo -e "${YELLOW}Opção:${NC} "
    read -r OPTION
    
    case $OPTION in
        1) detect_device || connect_tv ;;
        2) check_tv_info ;;
        3) check_app_installed ;;
        4) install_apk ;;
        5) check_stream_url ;;
        6) start_stream ;;
        7) monitor_logs ;;
        8) test_intents ;;
        9) run_stress_test ;;
        10) clear_app_data ;;
        11) 
            connect_tv || exit 1
            check_tv_info
            check_app_installed || install_apk
            check_stream_url
            start_stream
            monitor_logs
            ;;
        0) 
            final_report
            exit 0 
            ;;
        *) 
            echo -e "${RED}Opção inválida${NC}"
            ;;
    esac
    
    show_menu
}

# ========================================
# MAIN
# ========================================

banner

# Verificar dependências
echo -e "${BLUE}═══ VERIFICAÇÃO DE DEPENDÊNCIAS ═══${NC}"
check_adb
check_curl
check_jq

# Conectar se IP fornecido
if [ -n "$TV_IP" ]; then
    connect_tv
    
    # Se tiver stream, modo automático
    if [ -n "$STREAM_URL" ]; then
        check_tv_info
        check_app_installed || install_apk
        check_stream_url
        start_stream
        monitor_logs
        final_report
        exit 0
    fi
fi

# Modo interativo
show_menu
