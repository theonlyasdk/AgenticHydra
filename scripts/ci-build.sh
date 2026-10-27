#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
if ! java -version 2>&1 | head -1 | grep -q '"17\.'; then
  echo 'This workflow requires JDK 17.' >&2
  exit 1
fi
sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
if [[ -z "${HYDRA_KEYSTORE:-}" ]]; then
  export HYDRA_KEYSTORE="$PWD/preview.keystore"
  export HYDRA_STORE_PASSWORD='hydra-preview'
  if [[ ! -f "$HYDRA_KEYSTORE" ]]; then
    keytool -genkeypair -keystore "$HYDRA_KEYSTORE" -storepass "$HYDRA_STORE_PASSWORD" \
      -keypass "$HYDRA_STORE_PASSWORD" -alias hydra -keyalg RSA -keysize 2048 \
      -validity 3650 -dname 'CN=Agentic Hydra Preview,O=Agentic Hydra' -noprompt
  fi
fi
./gradlew --no-daemon --console=plain testReleaseUnitTest lintRelease assembleRelease
python3 scripts/check-contrast.py
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/Agentic-Hydra-preview.apk
