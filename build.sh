#!/bin/sh
# Builds anyauto.apk with the plain SDK tools (no Gradle), like the other apps here.
#   ./build.sh           build the APK
#   ./build.sh install   build, then adb install -r onto the attached phone and open it
#                        (ANDROID_SERIAL picks the device when several are attached)
#   ./build.sh verify    build, then print the manifest and signing details
#   ./build.sh clean     remove build/ (libs/ stays)
#
# A plain adb install is enough for the phone side (the preview) and for the DHU. A real car only
# shows AnyAuto when it is installed as if from Google Play: install anyauto.apk with UnknownAuto.
#
# Stages: deps (tools/fetch-deps.py) -> manifest merge (Google's manifest merger) ->
# aapt2 compile (app + each AAR) -> aapt2 link (R classes) -> aidl -> javac -> d8 ->
# zip + zipalign + apksigner -> manifest / dex checks.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
export PATH=$JAVA_HOME/bin:$PATH
SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/35.0.0
COMPILE_SDK=35
JAR=$SDK/platforms/android-$COMPILE_SDK/android.jar

APP_ID=com.mag.anyauto
TARGET_SDK=35
VERSION_CODE=8
VERSION_NAME=0.8
APK=anyauto.apk

if [ "$1" = "clean" ]; then rm -rf build $APK; echo "removed build/"; exit 0; fi

# ---- 1. libraries: resolve/download only when tools/deps.txt changed or files are missing
python3 -I tools/fetch-deps.py --roots tools/deps.txt --out libs --ensure
python3 -I tools/fetch-deps.py --roots tools/merger-deps.txt --out tools/merger --env jvm --ensure
. libs/deps.env
MERGER_CP=$(. tools/merger/deps.env; echo $LIB_JARS | tr ' ' ':')
LIB_CP=$(echo $LIB_JARS | tr ' ' ':')
if [ "$LIB_MIN_COMPILE_SDK" -gt $COMPILE_SDK ]; then
    echo "a library needs compileSdk $LIB_MIN_COMPILE_SDK, only android-$COMPILE_SDK is installed" >&2
    exit 1
fi
# 29 (Android 10): per-display focus, ActivityOptions.setLaunchDisplayId for other apps' activities
MIN_SDK=$(( LIB_MIN_SDK > 29 ? LIB_MIN_SDK : 29 ))

# build/libres keeps the compiled library resources between builds; the rest is rebuilt
mkdir -p build
find build -mindepth 1 -maxdepth 1 ! -name libres -exec rm -rf {} +
mkdir -p build/gen build/classes build/dex build/libres build/javares

# ---- 2. manifest: app manifest + every library manifest -> build/AndroidManifest.xml
java -cp "$MERGER_CP" com.android.manifmerger.Merger \
    --main AndroidManifest.xml \
    --libs "$(echo $LIB_MANIFESTS | tr ' ' ':')" \
    --property PACKAGE=$APP_ID \
    --property MIN_SDK_VERSION=$MIN_SDK --property TARGET_SDK_VERSION=$TARGET_SDK \
    --property VERSION_CODE=$VERSION_CODE --property VERSION_NAME=$VERSION_NAME \
    --remove-tools-declarations --log WARNING \
    --out build/AndroidManifest.xml

# ---- 3. resources: compile each AAR's res once (cached), the app's res every time
LINK_RES=""
for d in $LIB_RES_DIRS; do
    flat=build/libres/$(basename "$(dirname "$d")").zip
    [ -f "$flat" ] || { $BT/aapt2 compile --dir "$d" -o "$flat.tmp" && mv "$flat.tmp" "$flat"; }
    LINK_RES="$LINK_RES -R $flat"
done
$BT/aapt2 compile --dir res -o build/res.zip
ASSETS=""
for a in $LIB_ASSETS; do ASSETS="$ASSETS -A $a"; done
$BT/aapt2 link -o build/base.apk -I $JAR --manifest build/AndroidManifest.xml \
    --java build/gen --extra-packages "$LIB_R_PACKAGES" \
    --auto-add-overlay $LINK_RES -R build/res.zip $ASSETS

# ---- 4. AIDL (the Shizuku user-service interface), then compile app + R classes
$BT/aidl -p "$SDK/platforms/android-$COMPILE_SDK/framework.aidl" -I src -o build/gen \
    src/com/mag/anyauto/IDisplayService.aidl
javac --release 17 -nowarn -Xlint:-deprecation -Xlint:-options -encoding UTF-8 \
    -classpath "$JAR:$LIB_CP" -d build/classes $(find src build/gen -name '*.java')
(cd build/classes && jar cf ../classes.jar .)

# ---- 5. dex: app classes + every library jar
$BT/d8 --release --min-api $MIN_SDK --lib $JAR --output build/dex build/classes.jar $LIB_JARS

# ---- 6. package, align, sign
python3 -I tools/javares.py build/javares $LIB_JARS
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes*.dex)
if [ -n "$(ls -A build/javares)" ]; then (cd build/javares && zip -qr ../unsigned.apk .); fi
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f keys/anyauto.keystore ] || { mkdir -p keys; keytool -genkeypair -keystore keys/anyauto.keystore \
    -storepass android -keypass android -alias anyauto -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=AnyAuto" >/dev/null 2>&1; }
$BT/apksigner -J-enable-native-access=ALL-UNNAMED sign --ks keys/anyauto.keystore \
    --ks-pass pass:android --ks-key-alias anyauto --out $APK build/aligned.apk
rm -f $APK.idsig

# ---- 7. sanity checks: the car service, its permissions and the key classes
$BT/aapt2 dump xmltree --file AndroidManifest.xml $APK > build/manifest.txt
MUST="androidx.car.app.CarAppService androidx.car.app.category.NAVIGATION
      androidx.car.app.NAVIGATION_TEMPLATES androidx.car.app.ACCESS_SURFACE androidx.car.app.minCarApiLevel
      com.google.android.gms.car.application rikka.shizuku.ShizukuProvider
      moe.shizuku.manager.permission.API_V23 com.mag.anyauto.PreviewActivity"
# no routing: "navigate to" requests stay with Google Maps
MUST_NOT="androidx.car.app.action.NAVIGATE androidx.car.app.category.FEATURE_CLUSTER"
for m in $MUST; do
    grep -q "$m" build/manifest.txt || { echo "missing from the merged manifest: $m" >&2; exit 1; }
done
for m in $MUST_NOT; do
    ! grep -q "$m" build/manifest.txt || { echo "must not be in the merged manifest: $m" >&2; exit 1; }
done
for f in build/dex/classes*.dex; do $BT/dexdump "$f" | LC_ALL=C sed -n 's/^  Class descriptor  : .\(L[^;]*;\).*/\1/p'; done > build/dex-classes.txt
for c in Landroidx/car/app/CarAppService\; Landroidx/car/app/navigation/model/NavigationTemplate\; \
         Lrikka/shizuku/Shizuku\; Lrikka/shizuku/ShizukuProvider\; Lcom/mag/anyauto/R\; \
         Lcom/mag/anyauto/DisplayService\; Lcom/mag/anyauto/IDisplayService\$Stub\; \
         Lcom/mag/anyauto/car/AnyAutoService\; Lcom/mag/anyauto/Touch\; Lkotlin/Unit\;; do
    grep -qxF "$c" build/dex-classes.txt || { echo "missing from dex: $c" >&2; exit 1; }
done
# the Car App Library reads this jar resource at start-up; without it the host shows a black screen
unzip -l $APK | grep -q ' car-app-api.level$' || { echo "car-app-api.level is missing from the APK" >&2; exit 1; }

if [ "$1" = "verify" ]; then
    $BT/apksigner -J-enable-native-access=ALL-UNNAMED verify --print-certs $APK
    $BT/aapt2 dump badging $APK
    cat build/manifest.txt
fi
echo "built $(pwd)/$APK ($(( $(wc -c < $APK) / 1024 )) KB, minSdk $MIN_SDK, targetSdk $TARGET_SDK)"

if [ "$1" = "install" ]; then
    ADB=${ADB:-$(command -v adb || echo $SDK/platform-tools/adb)}
    "$ADB" install -r $APK
    "$ADB" shell am start -n $APP_ID/.MainActivity >/dev/null
    echo "opened AnyAuto on the phone"
fi
