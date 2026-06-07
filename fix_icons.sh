#!/bin/bash
# ============================================================
#  fix_icons.sh — Hanamboatra icon APK + PWA rehetra
#  Amorita ao Termux, ao anatin'ny project folder
#  Fomba fampiasana:
#    1. Asio baiko: bash fix_icons.sh
#    2. Na mametraka ny sary manokana: bash fix_icons.sh /path/to/icon.png
# ============================================================

set -e

# ── 1. Sary loharano ──────────────────────────────────────────
# Raha tsy misy argument, mampiasa ilay sary avy amin'ny Photoroom
SOURCE_ICON="${1:-/storage/emulated/0/Pictures/Photoroom/file_000000006da871f499ac504867.png}"

# Raha tsy hita ilay faila, jereo raha misy extension hafa
if [ ! -f "$SOURCE_ICON" ]; then
  # Jerena raha tsy misy extension
  for ext in png jpg jpeg webp; do
    if [ -f "/storage/emulated/0/Pictures/Photoroom/file_000000006da871f499ac504867.$ext" ]; then
      SOURCE_ICON="/storage/emulated/0/Pictures/Photoroom/file_000000006da871f499ac504867.$ext"
      break
    fi
  done
fi

if [ ! -f "$SOURCE_ICON" ]; then
  echo "❌ Tsy hita ilay sary: $SOURCE_ICON"
  echo "   Asio ny lalana tsara: bash fix_icons.sh /path/to/icon.png"
  exit 1
fi

echo "✅ Sary loharano: $SOURCE_ICON"

# ── 2. Jerena ny project folder ───────────────────────────────
PROJECT_DIR="$(pwd)"
APP_RES="$PROJECT_DIR/app/src/main/res"
APP_ASSETS_PWA="$PROJECT_DIR/app/src/main/assets/pwa"
ROOT_PWA="$PROJECT_DIR/pwa"
ROOT_ICONS="$PROJECT_DIR/icons"

if [ ! -f "$APP_RES/values/colors.xml" ]; then
  echo "❌ Tsy ao anatin'ny project folder ianao!"
  echo "   Mandehana aloha: cd /path/to/sms-getaway-app-main"
  exit 1
fi

echo "📁 Project: $PROJECT_DIR"

# ── 3. Install Python + Pillow raha tsy misy ──────────────────
echo ""
echo "📦 Fanamarinana python + Pillow..."
if ! python3 -c "from PIL import Image" 2>/dev/null; then
  echo "   Installing Pillow..."
  pip install Pillow -q
fi
echo "   ✅ Pillow OK"

# ── 4. Python script hanaovana resize + save rehetra ──────────
python3 << PYEOF
from PIL import Image
import os, shutil

src = "$SOURCE_ICON"
app_res = "$APP_RES"
app_pwa = "$APP_ASSETS_PWA"
root_pwa = "$ROOT_PWA"
root_icons = "$ROOT_ICONS"

img_orig = Image.open(src).convert("RGBA")
print(f"   Sary loharano: {img_orig.size[0]}x{img_orig.size[1]} px")

def save_icon(img, path, size):
    """Resize sy save icon"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    resized = img.resize((size, size), Image.LANCZOS)
    # Raha PNG, hazonina ny transparency
    resized.save(path, "PNG", optimize=True)
    print(f"   ✅ {size}x{size} → {path}")

def save_icon_rgb(img, path, size):
    """Resize sy save icon (RGB, tsy RGBA) — ho an'ny mipmap PNG sasany"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    resized = img.resize((size, size), Image.LANCZOS)
    # White background raha transparent
    bg = Image.new("RGB", (size, size), (255, 255, 255))
    if resized.mode == "RGBA":
        bg.paste(resized, mask=resized.split()[3])
    else:
        bg = resized.convert("RGB")
    bg.save(path, "PNG", optimize=True)
    print(f"   ✅ {size}x{size} → {path} (RGB)")

print("")
print("🎨 APK mipmap icons (Android launcher)...")

# mipmap sizes: mdpi=48, hdpi=72, xhdpi=96, xxhdpi=144, xxxhdpi=192
mipmap_sizes = {
    "mipmap-mdpi":    48,
    "mipmap-hdpi":    72,
    "mipmap-xhdpi":   96,
    "mipmap-xxhdpi":  144,
    "mipmap-xxxhdpi": 192,
}

for folder, size in mipmap_sizes.items():
    base = os.path.join(app_res, folder)
    save_icon(img_orig, os.path.join(base, "ic_launcher.png"), size)
    save_icon(img_orig, os.path.join(base, "ic_launcher_round.png"), size)

print("")
print("🎨 PWA icons (assets/pwa/icons)...")
save_icon(img_orig, os.path.join(app_pwa, "icons", "icon-192.png"), 192)
save_icon(img_orig, os.path.join(app_pwa, "icons", "icon-512.png"), 512)

print("")
print("🎨 PWA icons (pwa/icons — root)...")
os.makedirs(os.path.join(root_pwa, "icons"), exist_ok=True)
save_icon(img_orig, os.path.join(root_pwa, "icons", "icon-192.png"), 192)
save_icon(img_orig, os.path.join(root_pwa, "icons", "icon-512.png"), 512)

print("")
print("🎨 Root icons/ folder...")
os.makedirs(root_icons, exist_ok=True)
save_icon(img_orig, os.path.join(root_icons, "icon-192.png"), 192)
save_icon(img_orig, os.path.join(root_icons, "icon-512.png"), 512)

print("")
print("✅ Icons rehetra vita!")
PYEOF

# ── 5. Soloana ny ic_launcher.xml ho mampiasa PNG mivantana ───
echo ""
echo "🔧 Anarenana ic_launcher.xml (mipmap-anydpi-v26)..."

LAUNCHER_XML="$APP_RES/mipmap-anydpi-v26/ic_launcher.xml"
cat > "$LAUNCHER_XML" << 'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
XMLEOF

# Mamorona ic_launcher_foreground.png (= icon 108dp = 432px ho an'ny xxxhdpi)
echo "🔧 Mamorona ic_launcher_foreground amin'ny mipmap rehetra..."
python3 << PYEOF2
from PIL import Image
import os

src = "$SOURCE_ICON"
app_res = "$APP_RES"
img_orig = Image.open(src).convert("RGBA")

foreground_sizes = {
    "mipmap-mdpi":    81,
    "mipmap-hdpi":    108,
    "mipmap-xhdpi":   162,
    "mipmap-xxhdpi":  216,
    "mipmap-xxxhdpi": 432,
}

for folder, size in foreground_sizes.items():
    path = os.path.join(app_res, folder, "ic_launcher_foreground.png")
    resized = img_orig.resize((size, size), Image.LANCZOS)
    resized.save(path, "PNG", optimize=True)
    print(f"   ✅ ic_launcher_foreground {size}x{size} → {folder}/")
PYEOF2

# ── 6. Build APK ──────────────────────────────────────────────
echo ""
echo "🔨 Building APK..."
echo "   (Mety maharitra 2–5 minitra ity)"
echo ""

if [ -f "./gradlew" ]; then
  chmod +x ./gradlew
  ./gradlew assembleDebug 2>&1 | tail -20
  
  APK_PATH=$(find . -name "*.apk" -path "*/debug/*" | head -1)
  if [ -n "$APK_PATH" ]; then
    echo ""
    echo "🎉 APK vita! → $APK_PATH"
    
    # Copy APK ho any Downloads
    DEST="/storage/emulated/0/Download/sms-gateway-icon-fixed.apk"
    cp "$APK_PATH" "$DEST"
    echo "📲 Copied ho → $DEST"
    echo "   Sokafy ny Files app ary install!"
  else
    echo "❌ APK tsy hita. Jereo ny error ambony."
  fi
else
  echo "⚠️  gradlew tsy hita. Build manually:"
  echo "   ./gradlew assembleDebug"
fi

echo ""
echo "════════════════════════════════════════"
echo "✅ VITA DAHOLO!"
echo "   • APK icon: mipmap folders rehetra ✓"
echo "   • PWA icon: assets/pwa/icons/ ✓"
echo "   • PWA icon: pwa/icons/ ✓"
echo "   • Root icons/ ✓"
echo "════════════════════════════════════════"
