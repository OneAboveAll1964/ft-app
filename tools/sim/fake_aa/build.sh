#!/bin/sh
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
rm -rf out && mkdir -p out/classes out/dex
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 34
javac -source 11 -target 11 -cp "$JAR" -d out/classes $(find src -name '*.java') 2>/dev/null
"$BT/d8" --lib "$JAR" --min-api 29 --output out/dex $(find out/classes -name '*.class')
cp out/base.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --out out/test-aa.apk out/aligned.apk
echo "$PWD/out/test-aa.apk"
