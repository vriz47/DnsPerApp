#!/data/data/com.termux/files/usr/bin/bash
set -e
SDK=/data/data/com.termux/files/usr/opt/android-sdk
BT=$SDK/build-tools/36.1.0
PLATFORM=$SDK/platforms/android-35/android.jar
ROOT=$(cd "$(dirname "$0")" && pwd)
SRC=$ROOT/app/src/main
OUT=$ROOT/build
KS=$ROOT/debug.keystore

export PATH=$BT:$PATH

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/res" "$OUT/apk"

echo "[1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link \
  -o "$OUT/apk/base.apk" \
  -I "$PLATFORM" \
  --manifest "$SRC/AndroidManifest.xml" \
  -R "$OUT/res/res.zip" \
  --auto-add-overlay \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 35

echo "[3/6] javac"
javac -cp "$PLATFORM" \
  -d "$OUT/classes" \
  "$OUT/gen/com/dnsperapp/R.java" \
  $(find "$SRC/java" -name '*.java')

echo "[4/6] d8"
"$BT/d8" --release --lib "$PLATFORM" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "[5/6] package + zipalign"
cp "$OUT/dex/classes.dex" "$OUT/apk/"
(cd "$OUT/apk" && jar uf base.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/apk/base.apk" "$OUT/apk/aligned.apk"

echo "[6/6] sign"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -alias dnsperapp -keyalg RSA \
    -keysize 2048 -validity 10000 -storepass dnsperapp -keypass dnsperapp \
    -dname "CN=DNS Per App,O=DnsPerApp,C=ID"
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias dnsperapp \
  --ks-pass pass:dnsperapp --key-pass pass:dnsperapp \
  --out "$ROOT/app-debug.apk" "$OUT/apk/aligned.apk"

echo "DONE: $ROOT/app-debug.apk"
ls -lh "$ROOT/app-debug.apk"