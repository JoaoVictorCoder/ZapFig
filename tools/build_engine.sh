#!/usr/bin/env bash
# Gera o motor (Go -> AAR com gomobile) e separa as partes:
#  - app/libs/engine.aar        só as classes Java (vai dentro do APK)
#  - build/engine/engine-full.aar  com as libgojni.so (vira o release engine-v*)
# Requer ANDROID_HOME e ANDROID_NDK_HOME.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
mkdir -p build/engine app/libs
cd engine
# gomobile na mesma versão do go.mod (mudar a versão muda a API gerada)
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
export PATH="$PATH:$(go env GOPATH)/bin"
gomobile init
gomobile bind -target=android/arm64,android/arm -androidapi 28 \
  -javapkg=com.zapfig.engine -ldflags="-s -w" \
  -o "$ROOT/build/engine/engine-full.aar" ./bot
cd "$ROOT"
cp build/engine/engine-full-sources.jar app/libs/engine-sources.jar
cp build/engine/engine-full.aar app/libs/engine.aar
# O APK não leva o motor: ele é baixado na primeira vez que o bot liga.
zip -q -d app/libs/engine.aar 'jni/*' >/dev/null
echo "Motor $(grep -oP 'const version = "\K[^"]+' engine/bot/bot.go) • API $(python3 tools/engine_api.py app/libs/engine-sources.jar)"
