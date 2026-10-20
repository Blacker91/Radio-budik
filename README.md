# Free Radio pro Xiaomi Mi Smart Clock

Internetové rádio napsané na míru budíku **Xiaomi Mi Smart Clock** — čínské verzi **LX04** (小爱触屏音箱) s Androidem 8.1. Běžné rádiové aplikace na něm padají; tahle je postavená tak, aby se vyhnula všemu, na čem ostatní na tomhle hardwaru končí.

*Internet radio app tailored for the Xiaomi Mi Smart Clock (Chinese LX04, Android 8.1). Czech stations from radio-browser.info, zero dependencies.*

## Co umí

- **České stanice** z veřejné databáze [radio-browser.info](https://www.radio-browser.info) — bez registrace a API klíče, seznam se uloží a další starty fungují i bez sítě
- **Co právě hraje** — název skladby z ICY metadat streamu
- **Volba kvality** — stanice s víc streamy (MP3/AAC, různé bitraty) se seskupí pod jedno jméno
- **Oblíbené, vyhledávání** (bez ohledu na diakritiku) a **vlastní stanice** podle adresy streamu
- **Spořič s velkými hodinami** po nastavené době nečinnosti
- **Autostart** vybrané stanice po zapnutí budíku
- **Ovládání z mobilu** — webové rozhraní na `http://IP-budíku:8080`
- **Srovnávání zpoždění** — stream se v nastaveném intervalu tiše přepojí, takže se neopožďuje za živým vysíláním
- **Automatické obnovení spojení** po výpadku Wi-Fi
- **Online aktualizace** přes GitHub Releases

## Proč to funguje tam, kde jiné aplikace padají

| Problém na budíku | Řešení |
|---|---|
| `You must supply a layout_width attribute` (ConstraintLayout) | Jen `FrameLayout`/`LinearLayout` z frameworku |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | Čistá Java, žádné nativní knihovny |
| Chybí Google Play Services i WebView | **Nulové závislosti**, jen Android framework |
| Systém zabíjí aplikace na pozadí (DuraSpeed) | Přehrávání jako foreground service |

## Instalace

Hotové APK je v [Releases](../../releases). Do budíku přes ADB:

```bash
adb install -r radio-1.2.apk
```

Další verze si pak aplikace umí stáhnout sama (⚙ → Aktualizace).

## Sestavení ze zdrojáků

Potřebuješ JDK 21 a Android SDK (platform 33, build-tools 33.0.2). Gradle instalovat nemusíš, `gradlew` si stáhne správnou verzi sám. Podrobný postup pro Linux včetně podepsání je v [NAVOD.md](NAVOD.md).

```bash
./gradlew assembleRelease
```

Pro vydání nové verze je tu skript [`vydat.sh`](vydat.sh) — sestaví, podepíše, nahraje release a aktualizuje `verze.json`.

### Fork

Pokud si projekt forkneš, přepiš v `MainActivity.java` konstantu `DEFAULT_UPDATE_URL` a v `vydat.sh` proměnnou `REPO` na svůj účet. Aktualizace musí být podepsané stejným klíčem jako nainstalovaná verze, takže z cizího repozitáře by stejně nešly nainstalovat.

## Poznámky

- Stanice vysílající přes **HLS** jsou vynechané — `MediaPlayer` na Androidu 8.1 je spolehlivě nezvládá.
- Ne každá stanice posílá název skladby; u těch, které ne, to aplikace řekne.
- Testováno na LX04 s upravenou ROM. Globální verze **X04G** (Android 10 Go) by měla fungovat taky, ale není ověřená.

## Licence

[MIT](LICENSE)
