#!/usr/bin/env bash
# Builds the desktop apps into desktop/build/dist/:
#   frets-linux.tar.gz        portable: unpack, run neckscape/bin/neckscape
#   neckscape_<ver>_amd64.deb   installs to /opt/neckscape with a menu entry
#   frets-windows.zip         portable: unzip, double-click NeckScape.bat
#   frets-macos-arm64.zip     NeckScape.app for Apple silicon Macs (M1 and later)
#   frets-macos-x64.zip       NeckScape.app for Intel Macs
#
# Everything is built on Linux. jpackage can't make Windows installers here,
# so the Windows build is the app jar (with Windows natives) plus an official
# Temurin Windows JRE; likewise the macOS .app bundles are hand-assembled (jar
# with macOS natives + Temurin macOS JRE + a launcher script) and unsigned.
# jpackage's own .deb needs fakeroot (not installed, needs
# sudo), so the .deb is assembled with dpkg-deb --root-owner-group instead.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=1.2.0
DIST="$ROOT/desktop/build/dist"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/neckscape-build"
# jpackage (needed by createDistributable) isn't in Android Studio's JBR.
export JAVA_HOME="${JAVA_HOME_JPACKAGE:-$(ls -d "$HOME"/.jdks/jdk-21* | tail -1)}"
[[ -x "$JAVA_HOME/bin/jpackage" ]] || { echo "need a JDK with jpackage (set JAVA_HOME_JPACKAGE)"; exit 1; }

gradle() { (cd "$ROOT" && ./gradlew --console=plain -q "$@"); }

# Fetches an Adoptium Temurin 21 package once and checks its SHA-256.
temurin() { # os arch image_type -> path
    local json url sum name
    json=$(curl -sf --retry 4 --retry-all-errors "https://api.adoptium.net/v3/assets/latest/21/hotspot?os=$1&architecture=$2&image_type=$3&vendor=eclipse")
    read -r url sum name < <(python3 -I -c 'import json,sys;p=json.load(sys.stdin)[0]["binary"]["package"];print(p["link"],p["checksum"],p["name"])' <<<"$json")
    mkdir -p "$CACHE"
    [[ -f "$CACHE/$name" ]] || curl -sfL --retry 4 -o "$CACHE/$name" "$url"
    echo "$sum  $CACHE/$name" | sha256sum -c --quiet - >&2
    echo "$CACHE/$name"
}

rm -rf "$DIST" && mkdir -p "$DIST"

echo "== Linux"
gradle -Pneckscape.os=linux :desktop:createDistributable
APP="$ROOT/desktop/build/compose/binaries/main/app/neckscape"
tar -czf "$DIST/frets-linux.tar.gz" -C "$(dirname "$APP")" neckscape

DEB="$(mktemp -d)"
trap 'rm -rf "$DEB"' EXIT
mkdir -p "$DEB/DEBIAN" "$DEB/opt" "$DEB/usr/bin" "$DEB/usr/share/applications"
cp -a "$APP" "$DEB/opt/neckscape"
chmod 755 "$DEB" && chmod -R go-w "$DEB"
ln -s /opt/neckscape/bin/neckscape "$DEB/usr/bin/neckscape"
cat > "$DEB/usr/share/applications/neckscape.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=NeckScape-Navigator
Comment=Scales and chords on a guitar fretboard
Exec=/opt/neckscape/bin/neckscape
Icon=/opt/neckscape/lib/neckscape.png
Categories=AudioVideo;Audio;Education;
EOF
cat > "$DEB/DEBIAN/control" <<EOF
Package: neckscape
Version: $VERSION
Architecture: amd64
Maintainer: crsnow
Depends: libc6, libasound2t64 | libasound2, libx11-6, libxext6, libxrender1, libxtst6, libxi6, libfontconfig1
Description: Scales and chords on a guitar fretboard
 Maps every scale (by family and mode) and chord type onto the fretboard,
 finds playable chord shapes, and plays notes and chords.
EOF
dpkg-deb --root-owner-group -Zxz --build "$DEB" "$DIST/neckscape_${VERSION}_amd64.deb" >/dev/null

echo "== Windows"
gradle -Pneckscape.os=windows :desktop:packageUberJarForCurrentOS
JAR="$(ls -t "$ROOT"/desktop/build/compose/jars/neckscape-*.jar | head -1)"
grep -q 'skiko-windows-x64.dll' <(unzip -l "$JAR") || { echo "jar lacks Windows natives"; exit 1; }
WIN="$(mktemp -d)"
trap 'rm -rf "$DEB" "$WIN"' EXIT
mkdir -p "$WIN/NeckScape"
cp "$JAR" "$WIN/NeckScape/NeckScape.jar"
cp "$ROOT/icons/neckscape.ico" "$WIN/NeckScape/NeckScape.ico"
unzip -q "$(temurin windows x64 jre)" -d "$WIN"
mv "$WIN"/jdk-*-jre "$WIN/NeckScape/runtime"
printf '@echo off\r\nstart "" "%%~dp0runtime\\bin\\javaw.exe" -jar "%%~dp0NeckScape.jar"\r\n' > "$WIN/NeckScape/NeckScape.bat"
printf 'NeckScape-Navigator for Windows\r\n\r\nDouble-click NeckScape.bat to start. Nothing to install; delete the folder to remove.\r\nFor a desktop shortcut with the logo: right-click NeckScape.bat > Show more options > Create shortcut, then shortcut Properties > Change Icon > NeckScape.ico.\r\nIncludes Eclipse Temurin Java runtime (GPLv2 with Classpath Exception), see runtime\\legal.\r\n' > "$WIN/NeckScape/README.txt"
(cd "$WIN" && zip -qr "$DIST/frets-windows.zip" NeckScape)

# macOS: NeckScape.app/Contents/{Info.plist, MacOS/NeckScape, Resources/NeckScape.jar, runtime/}
macos() { # gradle-os temurin-arch zip-suffix description
    gradle -Pneckscape.os="$1" :desktop:packageUberJarForCurrentOS
    local jar mac app
    jar="$(ls -t "$ROOT"/desktop/build/compose/jars/neckscape-*.jar | head -1)"
    grep -q "libskiko-$1.dylib" <(unzip -l "$jar") || { echo "jar lacks $1 natives"; exit 1; }
    mac="$(mktemp -d)"
    app="$mac/NeckScape.app/Contents"
    mkdir -p "$app/MacOS" "$app/Resources"
    cp "$jar" "$app/Resources/NeckScape.jar"
    cp "$ROOT/icons/neckscape.icns" "$app/Resources/NeckScape.icns"
    tar -xzf "$(temurin mac "$2" jre)" -C "$mac"
    mv "$mac"/jdk-*-jre "$app/runtime"
    cat > "$app/MacOS/NeckScape" <<'LAUNCHER'
#!/bin/sh
DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$DIR/runtime/Contents/Home/bin/java" -Xdock:name=NeckScape -Dapple.awt.application.name=NeckScape -jar "$DIR/Resources/NeckScape.jar"
LAUNCHER
    chmod 755 "$app/MacOS/NeckScape"
    cat > "$app/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>NeckScape</string>
  <key>CFBundleDisplayName</key><string>NeckScape-Navigator</string>
  <key>CFBundleIdentifier</key><string>com.crsnow.neckscape</string>
  <key>CFBundleExecutable</key><string>NeckScape</string>
  <key>CFBundleIconFile</key><string>NeckScape</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$VERSION</string>
  <key>LSMinimumSystemVersion</key><string>11.0</string>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
    cat > "$mac/README.txt" <<README
NeckScape-Navigator for macOS ($4)

Drag NeckScape.app to Applications. The app isn't signed by Apple, so the first
time: right-click (or Control-click) NeckScape.app, choose Open, then Open again.
On macOS 15 or later, if there's no Open button: System Settings > Privacy &
Security > scroll down > "Open Anyway". Or in Terminal:
    xattr -dr com.apple.quarantine /Applications/NeckScape.app

Includes the Eclipse Temurin Java runtime (GPLv2 with Classpath Exception),
see NeckScape.app/Contents/runtime/Contents/Home/legal.
README
    (cd "$mac" && zip -qry "$DIST/frets-macos-$3.zip" NeckScape.app README.txt)
    rm -rf "$mac"
}
echo "== macOS"
macos macos-arm64 aarch64 arm64 "Apple silicon: M1 and later"
macos macos-x64 x64 x64 "Intel"

ls -la "$DIST"
