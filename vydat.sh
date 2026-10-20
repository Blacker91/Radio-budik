#!/bin/bash
#
# Vyda novou verzi Free Radia: sestavi, podepise, nahraje na GitHub
# a vyrobi verze.json, ze ktereho se budiky dozvi, ze je neco noveho.
#
# Pouziti:
#   ./vydat.sh "Co je v teto verzi noveho"
#
# Prvni spusteni potrebuje:
#   sudo apt install gh
#   gh auth login
#   gh repo create radio-budik --public      # jednou, pokud repo nemas
#
set -euo pipefail

# ---------- co si nastav ----------
REPO="${REPO:-Blacker91/Radio-budik}"        # tvuj ucet/repozitar na GitHubu
KEYSTORE="${KEYSTORE:-$HOME/budik.keystore}"
ALIAS="${ALIAS:-budik}"
BUILD_TOOLS="${BUILD_TOOLS:-$HOME/android-sdk/build-tools/33.0.2}"
# ----------------------------------

NOVINKY="${1:-Opravy a vylepšení}"
KOREN="$(cd "$(dirname "$0")" && pwd)"
cd "$KOREN"

if [[ "$REPO" == ZMENME/* ]]; then
    echo "CHYBA: vypln REPO (uprav skript, nebo spust: REPO=ucet/repo ./vydat.sh ...)" >&2
    exit 1
fi

# ---------- precti verzi z build.gradle ----------
VC=$(grep -oP 'versionCode\s+\K[0-9]+' app/build.gradle | head -1)
VN=$(grep -oP 'versionName\s+"\K[^"]+' app/build.gradle | head -1)

if [[ -z "$VC" || -z "$VN" ]]; then
    echo "CHYBA: v app/build.gradle nejde precist versionCode/versionName" >&2
    exit 1
fi
echo "== Vydávám verzi $VN (versionCode $VC)"

# ---------- pojistka: uz takova verze na GitHubu je? ----------
if gh release view "v$VN" --repo "$REPO" >/dev/null 2>&1; then
    echo "CHYBA: release v$VN uz existuje." >&2
    echo "       Zvys versionCode i versionName v app/build.gradle." >&2
    exit 1
fi

# ---------- sestaveni ----------
echo "== Sestavuji"
gradle assembleRelease

UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -f "$UNSIGNED" ]] || { echo "CHYBA: $UNSIGNED nevznikl" >&2; exit 1; }

# ---------- podpis ----------
# POZOR: musi to byt vzdy TENTYZ klic, jinak Android novou verzi odmitne
# a budik by se musel preinstalovat od nuly (prisel bys o nastaveni).
[[ -f "$KEYSTORE" ]] || { echo "CHYBA: chybí keystore $KEYSTORE" >&2; exit 1; }

echo "== Podepisuji"
APK="radio-$VN.apk"
rm -f "$APK" zarovnane.apk
"$BUILD_TOOLS/zipalign" -p 4 "$UNSIGNED" zarovnane.apk
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" \
    --out "$APK" zarovnane.apk
rm -f zarovnane.apk
"$BUILD_TOOLS/apksigner" verify "$APK" >/dev/null
echo "   $APK ($(du -h "$APK" | cut -f1))"

# ---------- release na GitHubu ----------
echo "== Nahrávám na GitHub"
gh release create "v$VN" "$APK" \
    --repo "$REPO" \
    --title "Free Radio $VN" \
    --notes "$NOVINKY"

APK_URL="https://github.com/$REPO/releases/download/v$VN/$APK"

# ---------- verze.json ----------
# Tenhle soubor se budik chodi ptat. Musi lezet v hlavni vetvi repozitare.
echo "== Aktualizuji verze.json"
cat > verze.json <<EOF
{
  "versionCode": $VC,
  "versionName": "$VN",
  "url": "$APK_URL",
  "novinky": "$(echo "$NOVINKY" | sed 's/"/\\"/g')"
}
EOF

# commit jen souboru verze.json, zdrojaky si resis sam
git add verze.json
git commit -m "verze $VN" >/dev/null
git push

VETEV=$(git rev-parse --abbrev-ref HEAD)
echo
echo "== Hotovo"
echo "Adresa pro budík:"
echo "  https://raw.githubusercontent.com/$REPO/$VETEV/verze.json"
echo
echo "Vlož ji na budíku do ⚙ → Aktualizace → Adresa,"
echo "nebo pohodlněji z mobilu přes webové rozhraní budíku."
