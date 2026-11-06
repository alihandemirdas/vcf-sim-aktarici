#!/bin/bash
# Gradle'sız derleme: aapt2 + javac + d8 + apksigner
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"

rm -rf build
mkdir -p build/gen build/classes

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
    --min-sdk-version 23 --target-sdk-version 34 --version-code 1 --version-name 1.0 build/res.zip

javac -nowarn --release 8 -cp "$JAR" -encoding UTF-8 \
    -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 23 --lib "$JAR" --output build $(find build/classes -name '*.class')

(cd build && zip -q base.apk classes.dex)
"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk

if [ ! -f imza.keystore ]; then
    keytool -genkeypair -keystore imza.keystore -storepass android -keypass android -alias vcfsim \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=VCF SIM Aktarici" >/dev/null
fi
"$BT/apksigner" sign --ks imza.keystore --ks-pass pass:android --key-pass pass:android \
    --out VCF-SIM-Aktarici.apk build/aligned.apk
rm -f VCF-SIM-Aktarici.apk.idsig
echo "Hazır: $(pwd)/VCF-SIM-Aktarici.apk"
