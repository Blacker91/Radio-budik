# Rádio pro budík LX04 — sestavení APK

Aplikace psaná přímo na míru Xiaomi Mi Smart Clock (LX04) s custom ROM SP6.

## Tři obrazovky

**1. Seznam stanic** — loga, názvy, kodek a bitrate. Nahoře malé hodiny, tlačítka pro nastavení a obnovení seznamu.

**2. Právě hraje** — logo stanice roztažené jako ztmavené pozadí, přes něj velký název stanice a pod ním právě hrající skladba. Ta se sama aktualizuje, jak se mění.

**3. Hodiny** — po nastavené době nečinnosti se přepne na černou obrazovku s velkými hodinami a datem. Dole decentně název stanice, která hraje dál. Klepnutím kamkoliv se vrátíš zpátky.

## Další funkce

- **Seznam stanic** stažený z radio-browser.info (české stanice podle popularity), uložený lokálně — další starty jsou okamžité a fungují i bez sítě
- **Co právě hraje** — čte se z ICY metadat streamu (pole `StreamTitle`), stejně jako to dělají komerční appky
- **Autostart** — podrž prst na stanici a nastav, že se má spustit po zapnutí budíku
- **Přehrávání na pozadí** jako foreground service s notifikací (aby appku nezabil DuraSpeed ani launcher)
- **Loga stanic** se stáhnou jednou a uloží na disk
- **Online aktualizace** — budík se jednou denně podívá na `verze.json` na GitHubu, a když je tam vyšší `versionCode`, nabídne instalaci. Viz samostatná kapitola níž.
- **Srovnávání zpoždění** — při každém zakolísání sítě přehrávač počká, než se doplní buffer, a pak pokračuje normální rychlostí. Rozdíl se nikdy nedohoní a za pár hodin naroste na minuty. Aplikace se proto sama v nastaveném intervalu přepojí na stejnou stanici (výchozí je každou hodinu) — zvuk se přeruší asi na vteřinu a stream je zase na živé úrovni. Nastavení je v ⚙ → „Srovnávat zpoždění vysílání", jde i vypnout.

## Proč by to mělo fungovat tam, kde ostatní appky padaly

| Problém, na který jsme naráželi | Jak je vyřešen |
|---|---|
| `You must supply a layout_width attribute` (ConstraintLayout) | Žádný ConstraintLayout — pouze `LinearLayout` z frameworku |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | Žádné nativní knihovny, čistá Java → funguje na jakékoli architektuře |
| Chybějící Google Play Services | Nulové závislosti, jen Android framework |
| Otáčení obrazovky | `android:screenOrientation="landscape"` natvrdo v manifestu |
| Zabíjení na pozadí | Foreground service s trvalou notifikací |

---

## Sestavení na Kali

### 1. Java

```bash
sudo apt install openjdk-17-jdk unzip
java -version
```

### 2. Android SDK (command line tools)

```bash
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
# Stáhni z https://developer.android.com/studio#command-line-tools-only
# (soubor commandlinetools-linux-*.zip)
unzip ~/Stažené/commandlinetools-linux-*.zip
mv cmdline-tools latest
```

Nastav proměnné (přidej i do `~/.zshrc`, ať to platí trvale):

```bash
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
```

Doinstaluj komponenty:

```bash
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-33" "build-tools;33.0.2"
```

### 3. Gradle

Instalovat nemusíš. Projekt obsahuje `gradlew`, který si při prvním sestavení stáhne správnou verzi Gradlu (8.5) sám.

### 4. Sestavení

```bash
cd ~/RadioBudik
./gradlew assembleRelease
```

Výsledek: `app/build/outputs/apk/release/app-release-unsigned.apk`

### 5. Podepsání

APK musí být podepsané, jinak ho Android odmítne. Klíč vytvoříš jednou a používáš pořád:

```bash
keytool -genkey -v -keystore ~/budik.keystore \
  -alias budik -keyalg RSA -keysize 2048 -validity 10000
```

```bash
cd ~/RadioBudik/app/build/outputs/apk/release

$ANDROID_HOME/build-tools/33.0.2/zipalign -v -p 4 \
  app-release-unsigned.apk radio-zarovnane.apk

$ANDROID_HOME/build-tools/33.0.2/apksigner sign \
  --ks ~/budik.keystore --ks-key-alias budik \
  --out radio.apk radio-zarovnane.apk
```

### 6. Instalace do budíku

```bash
adb install -r radio.apk
```

---

## Online aktualizace

Místo chození s kabelem od budíku k budíku si každý budík stáhne novou verzi sám. Funguje to i na budících v jiných domácnostech, protože se tahá z internetu, ne z domácí sítě.

### Jak to funguje

Budík se podívá na malý soubor `verze.json`:

```json
{
  "versionCode": 2,
  "versionName": "1.1",
  "url": "https://github.com/ucet/radio-budik/releases/download/v1.1/radio-1.1.apk",
  "novinky": "Co je v téhle verzi nového"
}
```

Když je `versionCode` vyšší než nainstalovaný, nabídne instalaci. Porovnává se **jen tohle číslo**, ne `versionName` — proto ho musíš při každém vydání zvýšit.

### Příprava (jednou)

**1. Repozitář na GitHubu**

```bash
sudo apt install gh
gh auth login
cd ~/RadioBudik
git init && git add -A && git commit -m "první verze"
gh repo create radio-budik --public --source=. --push
```

**2. Doplnit svůj účet do skriptu**

Výchozí repozitář je `Blacker91/Radio-budik` — v `vydat.sh` (řádek `REPO=`) i v `MainActivity.java` (konstanta `DEFAULT_UPDATE_URL`). Pokud si projekt forkneš, přepiš obojí na svůj účet.

**3. Povolení v budíku**

Na budíku v ⚙ → Aktualizace → Zkontrolovat nyní. Když chybí povolení, appka to řekne a nabídne tlačítko do nastavení. **Stačí to zapnout jednou a platí to trvale**, i po restartu.

Kdyby ta obrazovka v ROMce nebyla, povolení se dá nastavit z počítače (taky jen jednou):

```bash
adb shell appops set cz.budik.radio REQUEST_INSTALL_PACKAGES allow
```

**4. Adresa aktualizací do budíku**

Vypisovat dlouhou adresu na dotykovém displeji je utrpení, proto to jde i z mobilu: otevři webové rozhraní budíku (`http://IP:8080`), rozbal **↻ Aktualizace aplikace**, vlož adresu a dej Uložit.

```
https://raw.githubusercontent.com/ucet/radio-budik/main/verze.json
```

### Vydání nové verze

Zvyš `versionCode` i `versionName` v `app/build.gradle`, a pak:

```bash
./vydat.sh "Srovnávání zpoždění, online aktualizace"
```

Skript sestaví APK, podepíše ho tvým klíčem, nahraje na GitHub jako release, vyrobí `verze.json` a pushne ho. Budíky si to vyzvednou nejpozději do 24 hodin, nebo hned po klepnutí na „Zkontrolovat nyní".

### Důležité

- **Keystore `~/budik.keystore` nikdy neztrať a zálohuj ho.** Android nainstaluje aktualizaci přes starou verzi jen tehdy, je-li podepsaná **stejným klíčem**. S jiným klíčem to odmítne jako cizí aplikaci a budík bys musel přeinstalovat od nuly, včetně ztráty nastavení a vlastních stanic.
- Bez rootu vyskočí při každé aktualizaci systémové „Instalovat?" — povolení se dává jednou, ale potvrzení je u každé instalace. S rootem (`adb shell su -c id` vypíše `uid=0`) se nainstaluje potichu.
- Při instalaci se aplikace zavře, takže se přehrávání přeruší. Proto se aktualizace nikdy nespustí sama — vždy až po tvém potvrzení.
- Repozitář může být i privátní, ale pak by budík potřeboval token. Pro APK bez citlivého obsahu je jednodušší nechat ho veřejný.

---

## Používání

- **Klepnutí na stanici** — spustí přehrávání a přepne na obrazovku „právě hraje"
- **Podržení prstu na stanici** — zapne/vypne autostart po zapnutí budíku
- **‹ Stanice** — zpátky na seznam
- **■** — zastaví přehrávání
- **↻** — znovu stáhne aktuální seznam stanic
- **⚙** — nastavení: vlastní stanice, za jak dlouho se přepnout na hodiny (nikdy / 15 s / 30 s / 1 / 2 / 5 / 10 minut, výchozí 1 minuta), zobrazení jen oblíbených, srovnávání zpoždění (nikdy / 15 / 30 minut / 1 / 2 / 4 hodiny, výchozí 1 hodina), aktualizace
- **Klepnutí na hodiny** — návrat na předchozí obrazovku

## Ladění

Kdyby něco padalo, chybu uvidíš takto:

```bash
adb logcat -c
# spusť aplikaci na budíku
adb logcat -d | grep -iE "FATAL|AndroidRuntime|cz.budik.radio"
```

## Poznámky

- Stanice, které vysílají přes **HLS**, jsou ze seznamu vynechané — `MediaPlayer` na Androidu 8.1 je spolehlivě nezvládá.
- **Ne všechny stanice posílají ICY metadata.** U těch, co je neposílají, zůstane řádek „co hraje" prázdný — to není chyba aplikace.
- Metadata se čtou každých 25 sekund krátkým vedlejším spojením (~16 kB). Interval jde změnit v `PlayerService.java`, konstanta `META_INTERVAL_MS`.
- **Ne každá stanice má v databázi logo.** Kde chybí, zůstane pozadí prostě tmavé. Některá loga jsou navíc ve formátu `.ico`, který Android neumí dekódovat — ta se přeskočí.
- Loga jsou často malá (32–256 px), takže roztažená na pozadí budou rozmazaná. Je to záměr — slouží jako barevný podklad, ne jako ostrý obrázek. Průhlednost se dá doladit v `activity_main.xml` u `imgBg` (`android:alpha`).
- Pokud by autostart nefungoval, vypni MediaTek DuraSpeed:
  `adb shell pm disable-user --user 0 com.mediatek.duraspeed`
