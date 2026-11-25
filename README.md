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
- 🔊 **Figurinha com áudio**: vídeo com `/sa` usa o som do vídeo; responder um áudio com `/sa` coloca o som na última figurinha
- 📐 `/s2` → figurinha esticada num quadrado perfeito (sem bordas)
- 🖼️ Responder figurinha com `/img` → volta a ser imagem, ou **GIF** se for animada
- ↩️ Responder qualquer foto/vídeo com `/s`
- 🏷️ Nome do pacote e autor gravados na figurinha (`/pack Nome | Autor` ou pelos Ajustes)
- 📥 `/baixar link` → baixa o vídeo (YouTube, Instagram, TikTok, X…) com yt-dlp, no próprio celular; `/mp3 link` → só o áudio
- Enquadramento: encaixar (fundo transparente), recortar ou esticar
- Botão nas **Configurações rápidas**, ligar junto com o celular, estatísticas, log
- Pareamento por **código** (não precisa escanear QR)
- 🔄 **Atualização pelo GitHub**: ao abrir o app ou ligar o bot (com internet), procura um Release novo e pergunta se quer atualizar
- Tudo processado no aparelho; a sessão não vai para backup na nuvem

## Instalação (usuário)
1. Baixe o APK em **Releases**: `arm64-v8a` (a maioria dos celulares), `armeabi-v7a` (celulares antigos) ou `universal`.
2. Abra o app, digite o número com 55 + DDD e toque em **LIGAR**.
3. No WhatsApp: ⋮ › Dispositivos conectados › Conectar dispositivo › **Conectar com número de telefone** › digite o código.
4. Mande uma foto na conversa com você mesmo.

Em Xiaomi/Poco/Redmi: ative **Início automático** e bateria **Sem restrições** para o ZapFig.

## Como o app e o bot se separam
O APK **não leva o bot**. O bot (motor em Go/whatsmeow) é publicado em releases próprios
`engine-v<versão>-<api>` e o app baixa na primeira vez que você toca em **LIGAR**.
Depois disso, quando sai um bot novo, o app baixa sozinho e é só reiniciar o bot
(Ajustes › Bot › Reiniciar). Assim dá pra corrigir/melhorar o bot **sem publicar app novo**.

- **Segurança:** cada release do bot tem um `engine.json` (versões + SHA-256) assinado com a
  mesma chave do APK. O app confere com o próprio certificado antes de carregar; release sem
  essa assinatura é ignorado.
- **Compatibilidade:** o `api` do manifesto é a impressão digital das classes Java geradas pelo
  gomobile (`tools/engine_api.py`). Se você mudar funções exportadas do motor ou o `Callback`,
  a API muda e é preciso um app novo. Mudanças só por dentro do Go (comandos, correções) não mudam a API.

| Quero publicar… | Faça |
|---|---|
| Só o bot (comandos novos, correções no Go) | suba `const version` em `engine/bot/bot.go` › **Actions › Release do bot › Run workflow** |
| O app (mudou Kotlin/telas ou a API do motor) | suba `versionName`/`versionCode` › **Actions › Release do app › Run workflow** (publica o app e um bot compatível) |

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
Requisitos: Go 1.26+, JDK 17+, Python 3, Android SDK (platform 35), NDK 27.2.12479018 e CMake 3.22.1.

```bash
# 1) motor: gera app/libs/engine.aar (só classes) e build/engine/engine-full.aar (com o motor)
(cd engine && go test ./...)
tools/build_engine.sh

# 2) APKs (saem em app/build/outputs/apk/release/)
./gradlew assembleRelease

# 3) (opcional) empacotar o bot assinado, como a action faz
python3 tools/package_engine.py build/engine/engine-full.aar app/libs/engine-sources.jar \
  <versao> build/engine/out chave.pem
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
Lembre de subir `versionCode`/`versionName` em `app/build.gradle.kts` a cada versão. A tag precisa bater com o `versionName` (`v1.0.1` ↔ `1.0.1`): é assim que o app sabe que tem versão nova.

## Licenças de terceiros
- [whatsmeow](https://github.com/tulir/whatsmeow): MPL-2.0
- [libwebp](https://chromium.googlesource.com/webm/libwebp): BSD (ver `app/src/main/cpp/libwebp/COPYING`)
- [youtubedl-android](https://github.com/yausername/youtubedl-android) / [yt-dlp](https://github.com/yt-dlp/yt-dlp): GPL-3.0 / Unlicense (inclui Python e FFmpeg)
- [libopus](https://opus-codec.org): BSD
- [go-sqlite3](https://github.com/mattn/go-sqlite3): MIT
