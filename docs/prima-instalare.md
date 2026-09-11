# Prima instalare pe unitate — pas cu pas

Ghid pentru ziua în care duci laptopul la mașină. Comenzile sunt pentru
**PowerShell**; în PowerShell 5.1 `&&` nu există, deci fiecare comandă e separată.

> **Citește întâi [Plasa de siguranță](#plasa-de-siguranță).**
> Regula scurtă: **nu dezinstala launcher-ul vechi** până nu merge ăsta nou.
> El e singura ta cale de întoarcere.

---

## Ce iei cu tine

- laptopul
- cablul USB care iese din mașină
- APK-ul: `app\build\outputs\apk\release\app-release.apk`
  (sau de pe GitHub → **Actions** → ultima rulare → **Artifacts**)

Folosește **release**, nu debug. Debug-ul are alt nume de pachet
(`ro.e92.launcher.debug`), deci se instalează în paralel ca aplicație separată
și nu devine niciodată launcher-ul real.

---

## 0. Acasă, înainte de plecare

Pune APK-ul undeva ușor de găsit și verifică că `adb` merge:

```powershell
$env:Path += ";C:\Users\Costi\AppData\Local\Android\Sdk\platform-tools"
```

```powershell
adb version
```

---

## 1. La mașină — pornirea conexiunii

Pe unitate: `Settings → Developer options → USB debugging` pornit.
Dacă „Developer options" nu apare: `Settings → About` și apeși de 7 ori pe
„Build number".

Conectezi cablul, apoi:

```powershell
adb devices
```

Trebuie să vezi un dispozitiv cu starea `device`. Dacă scrie `unauthorized`,
acceptă dialogul de pe ecranul unității (bifează „Always allow").

Dacă nu apare nimic, unitatea probabil nu expune ADB pe USB. Alternativa e prin
Wi-Fi, dacă sunteți în aceeași rețea:

```powershell
adb connect 192.168.1.xxx:5555
```

---

## 2. Densitatea — înainte de orice altceva

```powershell
adb shell wm size
```

```powershell
adb shell wm density
```

**De ce contează:** layout-ul folosește procente și proporții calculate din
pixeli măsurați, deci **nu se sparge** la altă densitate. Dar textul și
spațierile sunt calibrate pentru ~160 dpi.

| Raportează | Ce faci |
|---|---|
| 160 | nimic, e calibrat |
| 120 | textul iese mic — îmi trimiți numărul și recalibrez |
| 240 | totul iese mare — la fel |

**Notează numărul și trimite-mi-l.** Se reglează într-un singur fișier.

---

## 3. Instalarea

```powershell
adb install -r "C:\Users\Costi\Desktop\E92Launcher__v2.0\app\build\outputs\apk\release\app-release.apk"
```

Trebuie să scrie `Success`.

Dacă dă `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, există deja o versiune semnată cu
altă cheie. Dezinstaleaz-o întâi:

```powershell
adb uninstall ro.e92.launcher
```

---

## 4. Pornirea manuală — încă NU ca launcher

```powershell
adb shell am start -n ro.e92.launcher/ro.e92.launcher.ui.HomeActivity
```

Lasă într-o fereastră separată, cât timp testezi, urmărirea erorilor:

```powershell
adb logcat -s AndroidRuntime:E
```

Orice crash apare acolo instant. Dacă rămâne gol, totul e în regulă.

---

## 5. Permisiunile

Locație (viteza din GPS + numele rețelei Wi-Fi):

```powershell
adb shell pm grant ro.e92.launcher android.permission.ACCESS_FINE_LOCATION
```

```powershell
adb shell pm grant ro.e92.launcher android.permission.ACCESS_COARSE_LOCATION
```

Acces la notificări — de asta depinde **tot ce ține de media**: titlul piesei,
play/pause, lista de surse. Fără el, muzica prin Bluetooth nu se vede în
aplicație.

Verifică întâi ce e deja activat, ca să nu ștergi altceva:

```powershell
adb shell settings get secure enabled_notification_listeners
```

Dacă răspunsul e `null`, poți scrie direct:

```powershell
adb shell settings put secure enabled_notification_listeners ro.e92.launcher/ro.e92.launcher.media.E92NotificationListener
```

Dacă răspunsul conținea deja ceva, **nu suprascrie** — adaugă componenta la
lista existentă, separată prin `:`. Sau, mai simplu, din interfață:
`Settings → System → Notification access`.

---

## 6. Ce verifici, în ordinea asta

1. **Diagnostics** — `Settings → Launcher → Diagnostics`. Aici e tot ce contează:
   - `density` — confirmă numărul de la pasul 2
   - `last CAN broadcast` — dacă scrie „(niciunul)", unitatea nu trimite date CAN
     spre Android, deci viteza/turația rămân pe mock, iar Drive Sport nu se poate
     declanșa. **Dacă apare ceva, trimite-mi lista de chei.**
   - `bluetooth adapter` — dacă scrie „absent", modulul BC6 e pe MCU și Android
     nu-l vede deloc
   - `media sessions` — pornește muzică de pe telefon prin Bluetooth și vezi dacă
     numărul crește de la 0
2. **Butoanele iDrive** — `Settings → Button mapping`. Apeși rândul acțiunii,
   apoi butonul fizic. Se salvează singur, fără recompilare.
   De legat neapărat `TILT_LEFT` și `TILT_RIGHT` — n-au taste implicite.
3. **Cele 12 dale** — intră în fiecare, verifică că se deschid
4. **Bara vendorului** — dacă rămâne o bară cu „Back / Home" peste aplicație,
   **nu e** bara Android; e un overlay al producătorului și nu poate fi ascunsă
   din aplicație. Diagnostics arată dimensiunea reală a ferestrei: dacă zice
   1280x480 complet, noi ne-am făcut treaba.

---

## 7. Abia acum: îl faci launcher implicit

Apeși butonul **HOME** de pe unitate. Apare un selector cu launcher-ele
instalate — alegi **E92**, apoi **Always**.

Sau din adb:

```powershell
adb shell cmd package set-home-activity ro.e92.launcher/.ui.HomeActivity
```

Ca să vezi ce e implicit acum:

```powershell
adb shell cmd package query-activities -c android.intent.category.HOME
```

### Trebuie să ștergi interfața veche?

**Nu.** Android permite mai multe launchere în paralel; la HOME primești un
selector. Verificat: cu ambele instalate, apăsarea pe HOME deschide selectorul.

Mai mult — **nu o șterge deocamdată**. Cât timp e instalată, ai unde să te
întorci dacă ceva nu merge. O ștergi peste câteva zile, când ești sigur.

Dacă vrei să schimbi înapoi launcher-ul implicit:
`Settings → Apps → Default apps → Home app`.

---

## Plasa de siguranță

Dacă launcher-ul crapă în buclă sau nu mai poți ieși din el:

```powershell
adb uninstall ro.e92.launcher
```

Launcher-ul vechi revine automat. **Notează comanda asta înainte să pleci.**

Resetarea setărilor fără dezinstalare (maparea butoanelor, aplicațiile atribuite):

```powershell
adb shell pm clear ro.e92.launcher
```

Dacă nu mai ai deloc acces, nici măcar adb: pornirea în Safe Mode dezactivează
temporar launcher-ele terțe. Procedura diferă de la unitate la unitate, de obicei
ții apăsat pe butonul de power la boot.

---

## Ce e verificat și ce nu

**Verificat pe emulator, la 1280x480, pe build-ul de release cu minificare:**
toate cele 12 dale deschise pe două niveluri, rotiță cu wrap-around, BACK ținut
pe rădăcină, HOME înapoi la rădăcină, repornire la rece. Zero crash-uri, zero
cadre pierdute din 405, 24 MB memorie.

**Nu se poate verifica decât pe unitatea reală:**

- densitatea raportată
- keycode-urile controller-ului iDrive (cele din cod sunt presupuneri)
- dacă unitatea trimite date CAN spre Android
- dacă modulul Bluetooth BC6 e vizibil pentru Android sau e doar pe MCU
- dacă butonul RADIO ajunge la Android sau e tratat de CIC
- dacă bara vendorului stă peste aplicație
