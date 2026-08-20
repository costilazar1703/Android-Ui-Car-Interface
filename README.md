# E92 Launcher

Launcher Android pentru head-unit-ul de 10.25" (1280x480, Android 8.1 / API 27) dintr-un BMW E92.
Kotlin, Views + ConstraintLayout + ViewBinding, single Activity, fără Compose, fără DI.

Specificația completă: [`../bmw-launcher-prompt.md`](../bmw-launcher-prompt.md)

---

## Stare curentă

### Implementat
| Zonă | Detaliu |
|---|---|
| Launcher shell | `HOME` intent-filter, `singleTask`, immersive sticky cu re-aplicare, `BOOT_COMPLETED` pentru pre-warm |
| Meniu principal | rotiță 3D desenată în stânga + 10 meniuri în dreapta, 5 pe pagină, 2 pagini |
| `IDriveWheelView` | rim cu gradient radial, crestături, marcaj aprins care se rotește la indexul focusat |
| `ConnectorView` | curbă cubică cu glow între rotiță și rândul selectat |
| `FocusEngine` | graf explicit de focus, wrap-around, vecini per direcție, restaurare focus la BACK |
| `RotaryAccelerator` | salt de 2 / 5 elemente la rotire rapidă, reset la schimbarea de sens |
| `HardKeyRouter` | keycode → acțiune, persistat, cu mod „învață tasta" în Setări |
| `SplitScreen` | șablon „categorii stânga / detalii dreapta" partajat de Setări, Bluetooth, Telefon, Mesaje |
| `CanDataSource` | interfață + `Mock`, `Broadcast`, `GpsSpeed`; fuziune pe prioritate în `VehicleRepository`, throttle 10 Hz |
| Dashboard | `GaugeView` custom pe `Canvas`, zero alocări în `onDraw`, ac la 30 fps, numeric la 10 Hz |
| App drawer | grid orizontal pe 2 rânduri, iconițe pre-încărcate off-thread, `LruCache`; dublează ca selector de aplicații |
| Media | `MediaSessionManager` + `NotificationListenerService`: metadata, cover, transport, selector de surse |
| Conectivitate | stare Wi-Fi în bara de sus, SSID, auto-reconnect cu backoff, shortcut spre setări |
| Navigație | pop-up Waze / Chrome din meniu, lansare directă din butonul hard NAV, card next-turn din notificări |
| Diagnostic | log de KeyEvents, stare CAN, ultimul broadcast brut, densitate ecran, dimensiune fereastră, probe `su` |
| Setări | aplicații atribuite (CarPlay / Dashboard / Car Info / Nav / Browser), keymapping, sursă CAN, setări Android |

### Meniul principal

| # | Meniu | Ce face |
|---|---|---|
| 1 | Media / Radio | `MediaScreen` |
| 2 | Bluetooth Audio | sub-meniu → setările Bluetooth native ale unității |
| 3 | Telephone | ecran split: Recent calls / Contacts / Dial pad (placeholder, vezi OQ7) |
| 4 | Navigation | pop-up Waze / Chrome |
| 5 | Apple CarPlay | lansează pachetul atribuit din Setări |
| 6 | Car Info | lansează pachetul atribuit din Setări |
| 7 | Dashboard | lansează pachetul atribuit; dacă nu e atribuit, cadranele proprii |
| 8 | Settings | `SettingsScreen` |
| 9 | ConnectedDrive | deschide URL-ul în browser-ul configurat |
| 10 | Messages | placeholder |

Paginarea nu e o stare separată: **focusul e sursa de adevăr**, iar pagina îl urmează.
Rotești peste elementul 5 → pagina alunecă singură. Swipe-ul cu degetul mută focusul,
nu un al doilea model de stare.

Trei elemente se mișcă împreună la fiecare mutare de focus, conduse dintr-un singur loc:
iconița din centrul rotiței, unghiul marcajului de pe rim și ținta conectorului.

### Efectul de glow

Nu e `elevation`: pe API 27 umbra e neagră și necolorabilă — inutilă pe fundal negru.
[`bg_menu_row.xml`](app/src/main/res/drawable/bg_menu_row.xml) construiește halo-ul din
straturi `layer-list` inset progresiv, cu alfa crescătoare spre centru. Tot lanțul vizual
(fundal, tenta iconiței, culoarea textului) atârnă de `state_activated`, pe care îl setează
`FocusEngine` pe rândul-părinte; iconița și textul îl preiau prin `duplicateParentState`.
Zero linii de cod pentru sincronizarea lor.

### Neimplementat (intenționat)
- **Player local USB/SD** — depinde de Open Question 9. Dacă sursa e exclusiv Bluetooth, nici nu e necesar.
- **Telefon (HFP/PBAP)** — depinde de Open Question 7. Ecranul există ca placeholder onest, cu scurtături manuale.
- **`SerialCanDataSource`** (`/dev/ttyS*`) — depinde de Faza 0.3 (root). Abstracția e pregătită.
- **Cameră de marșarier** — non-goal explicit. Semnalul e citit și afișat, dar nu există overlay video.

---

## Build

Nu există Android SDK instalat pe mașina pe care a fost scris proiectul, deci **codul nu a fost compilat**.
Prima sincronizare în Android Studio trebuie tratată ca prima compilare reală.

1. Deschide folderul `E92Launcher` în Android Studio.
2. La primul sync, AS generează Gradle wrapper-ul (`gradlew`, `gradle-wrapper.jar`) — nu sunt în repo pentru că sunt binare.
3. Build → Generate Signed Bundle / APK, sau `assembleRelease`:

```bash
./gradlew assembleRelease
```

Release-ul e semnat cu cheia de debug (`app/build.gradle.kts`), ca APK-ul de pe stick să se instaleze fără keystore separat. E uz personal; schimbă dacă vrei altceva.

**JDK:** folosește JBR-ul din Android Studio (17 sau 21). Gradle 8.7 nu suportă JDK 22.

### AVD pentru testare
Creează un AVD custom **1280x480** cu densitatea reală a unității (vezi Faza 0.4 mai jos).
Pe emulator, tastatura PC-ului e mapată deja:

| Tastă | Acțiune |
|---|---|
| `←` / `→` | rotiță CCW / CW (parcurge cele 10 meniuri, pagina urmează) |
| `Enter` | apăsare rotiță |
| `↑` / `↓` | tilt (vecin în listă / trecere între panouri în ecranele split) |
| `Esc` | BACK |
| `H` | Home |
| `A` | App drawer |
| `M` / `N` / `P` / `R` | Media / Nav / Telefon / Radio |
| `O` | OPTION |
| `D` | Diagnostic |

Cu `canMode = auto` și fără CAN real, `MockCanDataSource` livrează un ciclu de condus plauzibil, deci dashboard-ul se dezvoltă complet fără mașină.

### Instalare pe unitate
```bash
adb connect <ip-unitate>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```
Sau `.apk` pe stick FAT32 → File Manager pe unitate.

După instalare, o dată:
1. Apasă HOME → alege E92 ca launcher implicit (bifează „Always").
2. Setări → **Notification access** → acordă (obligatoriu pentru Media și pentru cardul de next-turn).
3. Acordă permisiunea de locație la prompt (viteză GPS de fallback + citirea SSID-ului pe 8.1).

---

## Faza 0 — de rulat pe unitatea reală înainte de stratul de integrare

Trei necunoscute blochează codul de integrare. Ecranul de **Diagnostic** din aplicație
(`D` pe emulator, sau leagă-i o tastă) acoperă o parte din ele fără laptop.

### 0.1 Cum expune modulul CAN datele?
```bash
adb logcat | grep -iE "can|bus|speed|rpm|acc|mcu"
adb shell ls -l /dev/tty*
adb shell pm list packages | grep -iE "can|bus|car|mcu"
adb shell dumpsys activity services | grep -iE "can|mcu"
```
Când găsești acțiunea și cheile reale, editează **un singur loc**:
`CanBroadcastConfig.DEFAULT` din [`BroadcastCanDataSource.kt`](app/src/main/java/ro/e92/launcher/can/BroadcastCanDataSource.kt).
Ecranul de diagnostic afișează ultimul broadcast prins, cu tot cu extras necunoscute — poți face maparea direct în mașină.

### 0.2 Ce keycode-uri trimit rotița și butoanele?
```bash
adb shell getevent -l
```
Sau, fără laptop: deschide **Diagnostic** în aplicație și apasă fiecare buton — keycode-ul brut și acțiunea în care s-a tradus apar în stânga.
Apoi Setări → *Key mapping* → alege acțiunea → apasă tasta fizică. Maparea se salvează și supraviețuiește reinstalării.

Default-urile din `HardKeyRouter` sunt **presupuneri**. Prima mapare învățată le înlocuiește complet.

### 0.3 Există root?
```bash
adb shell su -c id
adb shell ls -ld /system/priv-app
```
Ecranul de diagnostic arată dacă binarul `su` există — indiciu, nu dovadă.
Cu root devine viabil `SerialCanDataSource`; fără el, CAN funcționează doar dacă vendorul trimite broadcast-uri.

### 0.4 Densitatea ecranului — de făcut PRIMUL
```bash
adb shell wm density
adb shell wm size
```
Ambele apar și în ecranul de diagnostic, la `density` și `usable dp`.

Layout-ul e construit pe procente (chain-uri + `Guideline`), deci **nu se sparge** la altă densitate.
Ce trebuie recalibrat e doar textul și padding-urile, toate într-un singur fișier:
[`res/values/dimens.xml`](app/src/main/res/values/dimens.xml).
Dacă unitatea raportează 240 dpi, ai ~853x320 dp — redu valorile cu ~1/3.

---

## Arhitectură pe scurt

```
E92Application → Services (ServiceLocator manual)
                   ├── Prefs                 SharedPreferences
                   ├── VehicleRepository     fuziune surse + throttle 10 Hz
                   │     └── CanDataSource   Mock | Broadcast | Gps  (+ Serial, dacă root)
                   ├── AppRepository         listă apps + LruCache iconițe
                   ├── HardKeyRouter         keycode → LauncherAction
                   ├── MediaHub              MediaSessionManager
                   └── ConnectivityMonitor   Wi-Fi + auto-reconnect

HomeActivity (singurul Activity)
   ├── HardKeyRouter → LauncherAction → Screen.onAction() → fallback global
   ├── FocusEngine   graf explicit, rotiță = next/prev, tilt = vecini
   └── ScreenStack   push/pop cu restaurarea focusului
         └── Screen  Home · Dashboard · AppDrawer · Media · Settings · Diagnostics · Phone
```

**Regula centrală:** nimic din UI nu vede keycode-uri sau protocoale de transport.
UI-ul vede `LauncherAction` și `VehicleState`. Faza 0 schimbă doar cele două implementări
de la marginea sistemului (`CanBroadcastConfig`, maparea de taste), nu ecranele.

## Note de performanță

- `GaugeView`: `Paint`/`Path`/`RectF` și coordonatele gradațiilor pre-alocate; `onDraw` nu alocă nimic.
  Textul se formatează în setter (10 Hz), nu la desenare.
- Fără `LAYER_TYPE_HARDWARE` pe cadrane — **intenționat**: conținutul se schimbă la fiecare frame,
  deci un layer hardware ar însemna re-render în textură + blit, adică exact costul de evitat.
- Throttling-ul datelor CAN e în repository (`sample(100)`), nu în View.
- `KeyEventLog` scrie într-un buffer circular pre-alocat — zero alocări în handler-ul de taste.
- `RecyclerView`: `setHasFixedSize(true)`, `itemAnimator = null`, `notifyItemChanged` punctual.
- Observatorii de date trăiesc într-un scope legat de vizibilitatea ecranului: un ecran acoperit nu colectează nimic.
