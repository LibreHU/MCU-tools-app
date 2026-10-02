#!/bin/sh
# Build de JacMCU sans Gradle : clang (jacbridge) + aapt2 + javac + d8/dx + jarsigner -> out/JacMCU.apk
# Avec un SDK Android : ANDROID_HOME (build-tools + platforms/android-28) suffit.
# Sinon : ANDROID_JAR=.../android.jar  AAPT2=.../aapt2  et  D8=.../d8  ou  DX_JAR=.../dx.jar
# Signature : KEYSTORE (defaut ./jacmcu.keystore, cree au besoin, non versionne), mot de passe KS_PASS (defaut android).
set -e
cd "$(dirname "$0")"
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-1.0}

if [ -n "$ANDROID_HOME" ]; then
	BT=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -n1)
	: "${ANDROID_JAR:=$ANDROID_HOME/platforms/android-28/android.jar}"
	: "${AAPT2:=$BT/aapt2}"
	[ -z "$DX_JAR" ] && : "${D8:=$BT/d8}"
	[ -x "$BT/apksigner" ] && APKSIGNER=$BT/apksigner
fi
for v in ANDROID_JAR AAPT2; do eval "x=\$$v"; [ -e "$x" ] || { echo "$v introuvable ($x)"; exit 1; }; done

OUT=out
rm -rf $OUT; mkdir -p $OUT/classes $OUT/stage/lib/arm64-v8a

echo "[1/6] jacbridge (aarch64)"
clang --target=aarch64-linux-gnu -O2 -ffreestanding -fno-stack-protector -nostdlib -static -fno-builtin \
      -fuse-ld=lld -Wl,-e,_start -o $OUT/stage/lib/arm64-v8a/libjacbridge.so native/jacbridge.c

echo "[2/6] ressources"
"$AAPT2" compile --dir res -o $OUT/res.zip
"$AAPT2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -o $OUT/base.apk $OUT/res.zip \
	--min-sdk-version 23 --target-sdk-version 28 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

echo "[3/6] javac"
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
	-d $OUT/classes $(find src -name '*.java')

echo "[4/6] dex"
if [ -n "$D8" ]; then
	"$D8" --release --min-api 23 --lib "$ANDROID_JAR" --output $OUT/stage $(find $OUT/classes -name '*.class')
else
	java -jar "$DX_JAR" --dex --min-sdk-version=23 --output=$OUT/stage/classes.dex $OUT/classes
fi

echo "[5/6] assemblage"
python3 apkpack.py merge $OUT/base.apk $OUT/unsigned.apk $OUT/stage

echo "[6/6] signature"
KEYSTORE=${KEYSTORE:-jacmcu.keystore}
KS_PASS=${KS_PASS:-android}
[ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" -alias jacmcu -keyalg RSA -keysize 2048 -validity 10000 \
	-storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=JacMCU, O=Dok-T" 2>/dev/null
if [ -n "$APKSIGNER" ]; then
	python3 apkpack.py align $OUT/unsigned.apk $OUT/aligned.apk
	"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --out $OUT/JacMCU.apk $OUT/aligned.apk
else
	jarsigner -keystore "$KEYSTORE" -storepass "$KS_PASS" -sigalg SHA256withRSA -digestalg SHA-256 \
		$OUT/unsigned.apk jacmcu >/dev/null
	python3 apkpack.py align $OUT/unsigned.apk $OUT/JacMCU.apk
fi
ls -l $OUT/JacMCU.apk
