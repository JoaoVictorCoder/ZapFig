# ZapFig

Seu número de WhatsApp vira um bot de figurinhas, rodando no próprio celular, sem servidor.
O bot só responde na conversa **com você mesmo**.

> ⚠️ Projeto independente e **não oficial**, sem ligação com o WhatsApp ou a Meta. Ele usa um protocolo
> não oficial (o mesmo do WhatsApp Web), o que vai contra os Termos de Serviço do WhatsApp e pode levar
> ao banimento do número. Use por sua conta e risco.

## Recursos
- 📷 Foto → figurinha (automático ou com `/s`)
- 🎞️ GIF / 🎬 vídeo → **figurinha animada** (WebP animado, até 8 s, ≤ 500 KB)
- ✍️ `/t texto` → figurinha de texto
- 🖼️ Responder figurinha com `/img` → volta a ser imagem
- ↩️ Responder qualquer foto/vídeo com `/s`
- 🏷️ Nome do pacote e autor gravados na figurinha (`/pack Nome | Autor` ou pelos Ajustes)
- Enquadramento: encaixar (fundo transparente), recortar ou esticar
- Botão nas **Configurações rápidas**, ligar junto com o celular, estatísticas, log
- Pareamento por **código** (não precisa escanear QR)
- Tudo processado no aparelho; a sessão não vai para backup na nuvem

## Instalação (usuário)
1. Baixe o APK em **Releases**: `arm64-v8a` (a maioria dos celulares), `armeabi-v7a` (celulares antigos) ou `universal`.
2. Abra o app, digite o número com 55 + DDD e toque em **LIGAR**.
3. No WhatsApp: ⋮ › Dispositivos conectados › Conectar dispositivo › **Conectar com número de telefone** › digite o código.
4. Mande uma foto na conversa com você mesmo.

Em Xiaomi/Poco/Redmi: ative **Início automático** e bateria **Sem restrições** para o ZapFig.

## Estrutura
```
engine/            motor em Go (whatsmeow): conexão, filtro do chat próprio, comandos, EXIF
  bot/bot.go       ciclo de vida, pareamento
  bot/handler.go   comandos e envio
  bot/exif.go      grava pacote/autor dentro do WebP
app/               app Android (Kotlin + Jetpack Compose)
  src/main/cpp/    libwebp + ponte JNI para WebP animado
  .../media/       conversão foto/vídeo/texto → figurinha
  .../ui/          telas (Início, Ajustes, Comandos, Log)
  BotService.kt    serviço em primeiro plano
```

## Compilar
Requisitos: Go 1.26+, JDK 17+, Android SDK (platform 35), NDK 27.2.12479018 e CMake 3.22.1.

```bash
# 1) motor Go -> AAR
cd engine
go test ./...
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
gomobile bind -target=android/arm64,android/arm -androidapi 28 \
  -javapkg=com.zapfig.engine -ldflags="-s -w" -o ../app/libs/engine.aar ./bot

# 2) APKs (saem em app/build/outputs/apk/release/)
cd ..
./gradlew assembleRelease
```

### Assinatura
Crie `keystore.properties` na raiz (ele está no `.gitignore`, **nunca suba pro GitHub**):
```
storeFile=../zapfig-release.jks
storePassword=...
keyAlias=zapfig
keyPassword=...
```
Sem esse arquivo o build usa a chave de debug.

**Guarde a keystore com cuidado.** Sem ela você não consegue lançar atualizações: o Android só aceita
atualizar um app assinado com a mesma chave.

### Release automático (GitHub Actions)
Em *Settings › Secrets and variables › Actions*, crie:

| Secret | Valor |
|---|---|
| `ZAPFIG_KEYSTORE_BASE64` | `base64 -w0 zapfig-release.jks` |
| `ZAPFIG_KEYSTORE_PASSWORD` | senha da keystore |
| `ZAPFIG_KEY_ALIAS` | `zapfig` |
| `ZAPFIG_KEY_PASSWORD` | senha da chave |

Depois: `git tag v1.0.0 && git push origin v1.0.0`. O workflow compila e publica os APKs no Release.
Lembre de subir `versionCode`/`versionName` em `app/build.gradle.kts` a cada versão.

## Licenças de terceiros
- [whatsmeow](https://github.com/tulir/whatsmeow): MPL-2.0
- [libwebp](https://chromium.googlesource.com/webm/libwebp): BSD (ver `app/src/main/cpp/libwebp/COPYING`)
- [go-sqlite3](https://github.com/mattn/go-sqlite3): MIT
