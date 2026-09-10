# Testarea pe unitatea din mașină

Ghid de teren pentru instalarea și verificarea launcher-ului pe head unit-ul de
10.25" (RK3399, Android 8.1 / API 27, 1280x480).

Comenzile sunt scrise pentru **PowerShell** pe Windows. În PowerShell 5.1 `&&`
**nu există** — comenzile se dau separat sau legate cu `;`.

> **Înainte de plecare:** citește secțiunea [Plasa de siguranță](#plasa-de-siguranță).
> E singura cale de ieșire dacă launcher-ul se blochează și nu mai ajungi la
> setările Android.

---

## 0. Pregătiri

Pe unitate, activează **USB debugging** din `Settings → Developer options`.
(Dacă „Developer options" nu apare: `Settings → About` și apeși de 7 ori pe
„Build number".)

Pe laptop, pune `adb` în PATH pentru sesiunea curentă:

```powershell
$env:Path += ";C:\Users\Costi\AppData\Local\Android\Sdk\platform-tools"
```

De unde iei APK-ul:

- compilat local → `app\build\outputs\apk\release\app-release.apk`
- sau din GitHub → tab-ul **Actions** → rularea dorită → secțiunea **Artifacts**

Folosește build-ul de **release**, nu debug. Debug-ul are `applicationId`
`ro.e92.launcher.debug` (sufix din `build.gradle.kts`), deci se instalează în
paralel ca aplicație separată și nu înlocuiește launcher-ul real.

---

## 1. Conectarea

```powershell
adb devices
```

Trebuie să vezi un dispozitiv cu starea `device`. Dacă scrie `unauthorized`,
acceptă dialogul de autorizare de pe ecranul unității.

Multe head unit-uri nu expun ADB pe USB, doar prin rețea:

```powershell
adb connect 192.168.1.xxx:5555
```

(IP-ul unității îl vezi în `Settings → Wi-Fi → rețeaua conectată`.)

---

## 2. Densitatea reală — primul lucru de verificat

```powershell
adb shell wm size
```

```powershell
adb shell wm density
```

**De ce contează:** toate valorile din `app/src/main/res/values/dimens.xml`
presupun ~160 dpi, adică 1280x480 px = 1280x480 dp. Layout-ul în sine folosește
procente (Guideline + weight), deci **nu se sparge** la altă densitate — dar
textul și padding-urile ies prea mari sau prea mici.

| Densitate raportată | Spațiu logic | Ce faci |
|---|---|---|
| 160 | 1280x480 dp | nimic, valorile sunt calibrate |
| 120 | 1706x640 dp | textul iese mic — crește valorile din `dimens.xml` |
| 240 | 853x320 dp | reduce tot cu ~1/3 |

Se ajustează într-un singur fișier: `dimens.xml`.

---

## 3. Instalarea

```powershell
adb install -r "C:\Users\Costi\Desktop\E92Launcher__v2.0\app\build\outputs\apk\release\app-release.apk"
```

`-r` reinstalează peste versiunea existentă păstrând datele. Dacă dă eroare de
semnătură (ai instalat înainte un build semnat altfel), dezinstalează întâi:
vezi [Plasa de siguranță](#plasa-de-siguranță).

Pornire manuală, fără să-l faci încă launcher implicit:

```powershell
adb shell am start -n ro.e92.launcher/ro.e92.launcher.ui.HomeActivity
```

---

## 4. Permisiuni (opțional, scapi de dialoguri)

Locație — necesară pentru viteza din GPS și pentru citirea SSID-ului pe 8.1:

```powershell
adb shell pm grant ro.e92.launcher android.permission.ACCESS_FINE_LOCATION
```

```powershell
adb shell pm grant ro.e92.launcher android.permission.ACCESS_COARSE_LOCATION
```

Acces la notificări — de asta depinde tot ce ține de media (titlu piesă,
play/pause, lista de surse) și cardul de next-turn din navigație.

Vezi întâi ce e deja activat, ca să nu ștergi altceva:

```powershell
adb shell settings get secure enabled_notification_listeners
```

Dacă răspunsul e `null`, poți scrie direct:

```powershell
adb shell settings put secure enabled_notification_listeners ro.e92.launcher/ro.e92.launcher.media.E92NotificationListener
```

Dacă răspunsul conținea deja ceva, adaugă componenta la lista existentă separată
prin `:`, altfel dezactivezi listener-ele care erau acolo.

Alternativ, din interfață: `Settings → System → Notification access`.

---

## 5. Urmărirea erorilor

Lasă comanda asta într-o fereastră separată cât timp testezi:

```powershell
adb logcat -s AndroidRuntime:E
```

Orice crash apare acolo instant. Pentru tot ce loghează aplicația:

```powershell
adb logcat --pid=$(adb shell pidof -s ro.e92.launcher)
```

---

## 6. Maparea butoanelor iDrive

Keycode-urile controller-ului **nu sunt cunoscute** — cele din `HardKeyRouter`
sunt presupuneri marcate „de confirmat în mașină". Ăsta e motivul principal al
drumului la mașină.

**Varianta comodă, din aplicație:**

`Settings → Button mapping` → apeși rândul acțiunii, apoi butonul fizic pe care
îl vrei legat. Se salvează singur în `SharedPreferences`, fără recompilare.

`Settings → Launcher → Diagnostics` îți arată evenimentele de tastă primite,
util ca să vezi dacă un buton ajunge sau nu până la Android.

**Varianta brută, dacă un buton pare că nu ajunge deloc:**

```powershell
adb shell getevent -l
```

Apeși butoanele fizice și te uiți ce apare. Dacă aici nu apare nimic, butonul nu
ajunge la Android (e tratat în firmware-ul unității sau merge direct la CIC) și
nu-l poți lega din aplicație.

**De legat neapărat:** `TILT_LEFT` și `TILT_RIGHT` nu au nicio tastă implicită.
Meniurile split sunt navigabile și fără ele (rotația trece singură în panoul de
detaliu), dar cu ele e mult mai natural.

---

## 7. Setarea ca launcher implicit

**Fă asta ultima**, după ce ai confirmat că totul merge.

Apeși HOME pe unitate → alegi **E92** → **Always**.

Sau din adb:

```powershell
adb shell cmd package set-home-activity ro.e92.launcher/.ui.HomeActivity
```

Ca să verifici ce launcher e implicit acum:

```powershell
adb shell cmd package resolve-activity -c android.intent.category.HOME
```

---

## Plasa de siguranță

Dacă launcher-ul crapă în buclă sau nu mai poți ieși din el, **dezinstalarea îți
dă înapoi launcher-ul vechi**:

```powershell
adb uninstall ro.e92.launcher
```

Notează comanda asta înainte să pleci de acasă.

Dacă nu mai ai deloc acces (nici măcar adb), pornirea în Safe Mode dezactivează
temporar launcher-ele terțe — procedura diferă de la unitate la unitate, de
obicei ține apăsat pe butonul de power la boot.

Ștergerea setărilor aplicației, fără dezinstalare:

```powershell
adb shell pm clear ro.e92.launcher
```

Asta resetează maparea butoanelor, aplicațiile atribuite și toate preferințele.

---

## Ordinea recomandată la mașină

1. `wm density` — **înainte de orice**, de asta depinde tot aspectul
2. Instalezi și pornești manual; verifici că nu crapă (`logcat` deschis)
3. Acorzi permisiunile
4. Mapezi butoanele iDrive din `Settings → Button mapping`
5. Verifici meniurile: ambele pagini, pop-up-ul de navigație, ecranele split
6. **Abia acum** îl setezi ca launcher implicit

Până la pasul 6 poți ieși oricând apăsând HOME.

---

## Ce mai e de confirmat pe unitatea reală

Lucruri care nu se pot verifica pe emulator:

- densitatea raportată și, implicit, calibrarea din `dimens.xml`
- keycode-urile reale ale controller-ului iDrive
- dacă bara vendorului „Back / Home" rămâne peste aplicație — dacă da, **nu e**
  bara de sistem Android, ci un overlay al producătorului (`SYSTEM_ALERT_WINDOW`)
  și nu poate fi ascunsă din aplicație; ecranul de Diagnostics arată dimensiunea
  reală a ferestrei, ca să-ți dai seama
- dacă butonul RADIO ajunge la Android sau e tratat de CIC
- dacă unitatea expune un dialer lansabil (pentru ecranul Telephone)
