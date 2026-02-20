# 🎮 FongMi/TV - Patch para Intents

Este patch permite abrir streams M3U8 diretamente de navegadores/apps externos.

## 📁 Arquivos

| Arquivo | Função |
|---------|--------|
| `AndroidManifest.xml` | Adiciona intent filters para receber URLs |
| `ExternalPlayActivity.java` | Activity que recebe e repassa URLs para o player |
| `.github/workflows/build.yml` | GitHub Actions para compilar automaticamente |

---

## 🚀 Como Compilar com GitHub Actions

### 1. Criar seu Fork

1. Vá para https://github.com/FongMi/TV
2. Clique em **Fork** (canto superior direito)
3. Nomeie como `fongmi-tv-intent` ou outro nome

### 2. Adicionar os arquivos do patch

No seu fork, crie a estrutura:

```
seu-fork/
├── fongmi-intent-patch/
│   ├── AndroidManifest.xml
│   ├── ExternalPlayActivity.java
│   └── .github/workflows/build.yml
```

### 3. Executar o Build

1. Vá na aba **Actions** do seu fork
2. Selecione "Build FongMi TV with Intent Support"
3. Clique **Run workflow**
4. Aguarde ~10-15 minutos
5. Baixe o APK em **Artifacts**

---

## 🎯 Como Usar no Android

Depois de instalar o APK modificado:

### Do seu site IPLINKS:
```javascript
// Clicando no botão FNG abre direto no FongMi
const url = 'http://servidor:8080/live/user/pass/123.m3u8';
window.location.href = `intent:${url}#Intent;action=android.intent.action.VIEW;type=video/*;package=com.fongmi.android.tv;end`;
```

### Compartilhamento:
1. Copie URL do stream
2. Compartilhar → FongMi TV
3. Reproduz automaticamente

---

## 🌐 Idiomas (Português)

O FongMi já tem inglês (`values-en/`). Para adicionar português:

1. Criar pasta: `app/src/main/res/values-pt-rBR/`
2. Criar arquivo: `strings.xml`
3. Traduzir textos
4. Recompilar

---

## ✅ Vantagens sobre VLC

- **ExoPlayer nativo** - Mais estável
- **Interface TV** - Otimizado para TV Box
- **Sem buffering** - Melhor gerenciamento
- **Suporte danmaku** - Legendas especiais
