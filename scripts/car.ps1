<#
  Ziua de masina: laptop si unitate pe hotspot-ul iPhone-ului. Scriptul gaseste
  unitatea, instaleaza ultima versiune, pune motorul Bluetooth E92, verifica tot
  si da VERDE sau ROSU. Poate fi rulat de cate ori e nevoie.

    .\scripts\car.ps1 prepare              tot, pana la verdict
    .\scripts\car.ps1 status               starea Bluetooth
    .\scripts\car.ps1 watch -Minutes 10    urmareste muzica si numara caderile
    .\scripts\car.ps1 play                 play/pauza de pe laptop
    .\scripts\car.ps1 revert               inapoi pe motorul KSW
    .\scripts\car.ps1 btcapture -Minutes 3 -Label idle-fara-telefon
                                           jurnalul stivei, restarturi, UART (deschide adb cat ruleaza)
    .\scripts\car.ps1 rprobe -Minutes 3 -Label ksw-tip6
                                           toate cadrele MCU in marsarier (deschide adb cat ruleaza)
    .\scripts\car.ps1 grant                permisiunea pentru marsarier, o data, prin adb
    .\scripts\car.ps1 reverse -Minutes 5   arata marsarierul si datele de la senzori
    .\scripts\car.ps1 scene-on             scena E92 in marsarier (tip camera KSW = 0)
    .\scripts\car.ps1 scene-off            inapoi la ecranul KSW (tip camera KSW = 1)
    .\scripts\car.ps1 mcu -Label "camera=3"  setarile de marsarier ale placii (camera, radar, track, origvideo)
    .\scripts\car.ps1 kswapps -Label off   dezactiveaza playerele KSW care apar singure (on = la loc, status)
    .\scripts\car.ps1 carplay-start -Label wireless
                                           starea USB / AutoPlay / Wi-Fi si porneste jurnalul PE UNITATE
                                           (merge si cand hotspot-ul cade in timpul CarPlay)
    .\scripts\car.ps1 carplay-fetch -Label wireless
                                           dupa incercare, cu hotspot-ul pornit: opreste jurnalul si il aduce

  Fara diacritice: PowerShell 5.1 citeste scripturile fara BOM ca ANSI.
#>
param(
    [Parameter(Position = 0)] [string] $Command = "prepare",
    [string] $Ip = "",
    [string] $Key = "6543",
    [string] $Apk = "",
    [int] $Minutes = 10,
    [string] $Label = "test"
)

$configFile = Join-Path $env:LOCALAPPDATA "e92diag.json"
if (-not $Apk) { $Apk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\release\app-release.apk" }

function Call([string] $base, [string] $path) {
    curl.exe -s --connect-timeout 2 --max-time 20 -H "X-Key: $Key" "$base$path"
}

function Shell([string] $base, [string] $cmd) {
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $cmd)
    curl.exe -s --connect-timeout 2 --max-time 30 -H "X-Key: $Key" --data-binary "@$tmp" "$base/shell"
    Remove-Item $tmp -ErrorAction SilentlyContinue
}

# Ce se uita la CarPlay: modulul pe USB, aplicatiile AutoPlay/AutoKit, Wi-Fi-ul unitatii.
$carplayState = @(
    "echo '== USB =='; for d in /sys/bus/usb/devices/*; do [ -f `$d/idVendor ] && echo `"`$(basename `$d) `$(cat `$d/idVendor):`$(cat `$d/idProduct) `$(cat `$d/manufacturer 2>/dev/null) / `$(cat `$d/product 2>/dev/null)`"; done",
    "echo '== aplicatii =='; pm list packages -f 2>/dev/null | grep -i -E 'manstep|autokit|autoplay|zlink|carlink|easyconn|carplay'",
    "echo '== versiuni =='; for p in `$(pm list packages 2>/dev/null | grep -i -E 'manstep|autokit|zlink|carlink|easyconn' | cut -d: -f2); do echo `$p; dumpsys package `$p | grep -E 'versionName|firstInstallTime|lastUpdateTime|enabled=' | head -5; done",
    "echo '== ruleaza =='; ps -A 2>/dev/null | grep -i -E 'manstep|autokit|zlink|carlink|easyconn'",
    "echo '== implicit pentru USB =='; dumpsys usb 2>/dev/null | grep -i -E 'device|default|package|permission' | head -40",
    "echo '== wifi =='; dumpsys wifi 2>/dev/null | grep -i -E 'Wi-Fi is|mWifiInfo|SSID|frequency' | head -10; ip addr 2>/dev/null | grep -E 'wlan|p2p|inet ' "
)

function Code([string] $base) {
    curl.exe -s -o NUL --connect-timeout 1 --max-time 3 -w "%{http_code}" -H "X-Key: $Key" "$base/help"
}

function Find-Unit {
    $candidates = @()
    if ($Ip) { $candidates += $Ip }
    else {
        if (Test-Path $configFile) {
            $saved = (Get-Content $configFile -Raw | ConvertFrom-Json).ip
            if ($saved) { $candidates += $saved }
        }
        $candidates += 2..30 | ForEach-Object { "172.20.10.$_" }
    }
    foreach ($c in $candidates) {
        $port = if ($c -match ":") { "" } else { ":8092" }
        $base = "http://$c$port"
        $code = Code $base
        if ($code -eq "200") {
            if (-not $Ip) {
                (@{ ip = $c; key = $Key } | ConvertTo-Json) | Out-File -Encoding utf8 $configFile
            }
            return $base
        }
        if ($code -eq "403") { throw "Unitatea e la $c, dar cheia $Key e gresita. Cheia e pe ecranul Diagnostic." }
    }
    return $null
}

function Read-Status([string] $base) {
    $map = @{}
    $text = Call $base "/bt"
    foreach ($line in @($text)) {
        $eq = "$line".IndexOf("=")
        if ($eq -gt 0) { $map["$line".Substring(0, $eq)] = "$line".Substring($eq + 1).Trim() }
    }
    return $map
}

function Install-Latest([string] $base, [string] $want) {
    $deadline = (Get-Date).AddMinutes(5)
    while ($true) {
        Write-Host "Trimit APK-ul pe unitate..."
        $answer = "$(curl.exe -s --max-time 300 -H "X-Key: $Key" -H "Content-Type: application/octet-stream" --data-binary "@$Apk" "$base/install")"
        # Fara permisiune, /install doar deschide setarea: trimitem iar dupa ce e data.
        if ($answer -notmatch "voie") { break }
        if ((Get-Date) -gt $deadline) { return $false }
        Write-Host ">>> PE UNITATE: activeaza 'Allow from this source', apoi BACK. Retrimit in 30 s."
        Start-Sleep 30
    }
    Write-Host ">>> PE UNITATE: apasa INSTALL, apoi OPEN (sau HOME)."
    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep 5
        $s = Read-Status $base
        if ($s.apk -eq $want) {
            Write-Host "Versiunea noua ruleaza."
            return $true
        }
    }
    return $false
}

$base = Find-Unit
if (-not $base) {
    Write-Output "ROSU: nu gasesc unitatea pe 172.20.10.2-30, port 8092."
    Write-Output "Verifica: laptopul si unitatea pe hotspot, launcher-ul deschis pe unitate."
    exit 2
}
Write-Output "Unitatea: $base"

switch ($Command) {
    "status" { Call $base "/bt" }
    "play" { Call $base "/bt/do?c=playpause" }
    "btcapture" {
        # Raspunde la o singura intrebare: stiva chiar se reseteaza, sau doar pica legatura?
        $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        $target = "$(([uri]$base).Host):5555"
        $out = Join-Path $PSScriptRoot "..\.unit-dump\bt-$(Get-Date -Format yyyyMMdd-HHmm)-$Label"
        New-Item -ItemType Directory -Force $out | Out-Null
        curl.exe -s --max-time 10 -H "X-Key: $Key" --data-binary "setprop service.adb.tcp.port 5555; setprop ctl.restart adbd" "$base/shell" | Out-Null
        Start-Sleep 4
        cmd /c "`"$adb`" connect $target" | Out-Null
        $jobs = @()
        try {
            cmd /c "`"$adb`" -s $target shell getprop > `"$out\getprop.txt`""
            cmd /c "`"$adb`" -s $target shell su 0 dmesg > `"$out\dmesg-start.txt`""
            cmd /c "`"$adb`" -s $target shell su 0 lsof > `"$out\lsof.txt`" 2>&1"
            cmd /c "`"$adb`" -s $target logcat -c"
            $jobs += Start-Process cmd -WindowStyle Hidden -PassThru -ArgumentList "/c `"`"$adb`" -s $target logcat -v time > `"$out\logcat.txt`"`""
            $jobs += Start-Process cmd -WindowStyle Hidden -PassThru -ArgumentList "/c `"`"$adb`" -s $target shell su 0 tail -n 0 -f /data/feasycom/log > `"$out\feasycom.txt`"`""
            Start-Sleep 2
            # Doar interogari: VER, PROFILE si FEATURE nu schimba nimic in stiva.
            foreach ($q in "VER", "PROFILE", "FEATURE", "AUTOCONN") {
                cmd /c "`"$adb`" -s $target shell `"printf 'AT+$q\r\n' > /dev/goc_serial`""
                Start-Sleep 1
            }
            $end = (Get-Date).AddMinutes($Minutes)
            $lastPid = ""
            $restarts = -1
            Write-Output "Inregistrez $Minutes minute in $out"
            while ((Get-Date) -lt $end) {
                $stackPid = "$(cmd /c "`"$adb`" -s $target shell pidof blueware")".Trim()
                if ($stackPid -ne $lastPid) {
                    $restarts++
                    $line = "$(Get-Date -Format HH:mm:ss) blueware pid='$stackPid'"
                    Write-Output $line
                    $line | Out-File -Append -Encoding ascii (Join-Path $out "stack-pid.txt")
                    $lastPid = $stackPid
                }
                Start-Sleep 3
            }
            cmd /c "`"$adb`" -s $target shell su 0 dmesg > `"$out\dmesg-end.txt`""
            cmd /c "`"$adb`" -s $target shell su 0 cat /data/feasycom/log > `"$out\feasycom-full.txt`""
        } finally {
            $jobs | ForEach-Object { taskkill /PID $_.Id /T /F 2>$null | Out-Null }
            cmd /c "`"$adb`" -s $target shell `"setprop service.adb.tcp.port -1; setprop ctl.restart adbd`"" 2>$null
            cmd /c "`"$adb`" disconnect $target" | Out-Null
        }
        Call $base "/events?n=400" | Out-File -Encoding utf8 (Join-Path $out "events.txt")
        $f = Join-Path $out "feasycom.txt"
        function Count([string] $pattern) { (Select-String -Path $f -Pattern $pattern -ErrorAction SilentlyContinue | Measure-Object).Count }
        Write-Output ""
        Write-Output "Procesul stivei s-a schimbat: $restarts ori"
        Write-Output "Stiva reinitializata (HCI stare 2): $(Count 'HCI_STATE_CHANGED_IND:2')"
        Write-Output "HFP ridicat: $(Count '>>:IB')   HFP cazut: $(Count 'HFP_SLC_DISCONNECTED')   A2DP cazut: $(Count 'A2DP_CLOSED')"
        Write-Output "Erori UART (h5): $(Count 'h5 bad')"
        Select-String -Path (Join-Path $out "feasycom-full.txt") -Pattern '^auth ' -ErrorAction SilentlyContinue | Select-Object -Last 1 | ForEach-Object { Write-Output "Licenta stivei: $($_.Line)" }
        Select-String -Path $f -Pattern '>>:(VER|PROFILE|FEATURE|AUTOCONN)' -ErrorAction SilentlyContinue | ForEach-Object { $_.Line }
        Select-String -Path (Join-Path $out "lsof.txt") -Pattern 'ttyS|goc_serial' -ErrorAction SilentlyContinue | Select-Object -First 8 | ForEach-Object { $_.Line }
    }
    "rprobe" {
        # Toate cadrele MCU pe care le vede EventCenter, cu logcat continuu, ca sa nu se piarda.
        $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        $target = "$(([uri]$base).Host):5555"
        $out = Join-Path $PSScriptRoot "..\.unit-dump\rprobe-$(Get-Date -Format yyyyMMdd-HHmm)-$Label"
        New-Item -ItemType Directory -Force $out | Out-Null
        curl.exe -s --max-time 10 -H "X-Key: $Key" --data-binary "setprop service.adb.tcp.port 5555; setprop ctl.restart adbd" "$base/shell" | Out-Null
        Start-Sleep 4
        cmd /c "`"$adb`" connect $target" | Out-Null
        $log = Join-Path $out "logcat.txt"
        $job = $null
        try {
            cmd /c "`"$adb`" -s $target logcat -c"
            $job = Start-Process cmd -WindowStyle Hidden -PassThru -ArgumentList "/c `"`"$adb`" -s $target logcat -v time > `"$log`"`""
            Write-Output "Inregistrez $Minutes minute in $out. Baga in R, misca volanul, apropie-te de un obstacol."
            Start-Sleep ($Minutes * 60)
        } finally {
            if ($job) { taskkill /PID $job.Id /T /F 2>$null | Out-Null }
            cmd /c "`"$adb`" -s $target shell `"setprop service.adb.tcp.port -1; setprop ctl.restart adbd`"" 2>$null
            cmd /c "`"$adb`" disconnect $target" | Out-Null
        }
        Call $base "/events?n=200" | Out-File -Encoding utf8 (Join-Path $out "events.txt")
        Write-Output "Tipuri de cadre MCU:"
        Select-String -Path $log -Pattern 'DataType0x\w+: bydata = (0xf2 0x\w+ 0x\w+ 0x\w+ 0x\w+|0xf2 0x\w+ 0x\w+ 0x\w+)' -ErrorAction SilentlyContinue |
            ForEach-Object { $_.Matches[0].Groups[1].Value } | Group-Object | Sort-Object Count -Descending |
            ForEach-Object { "  $($_.Count)  $($_.Name)" }
        Write-Output "Linii radar sau traseu:"
        Select-String -Path $log -Pattern 'setTrackData|radar KSW|RadarInfo|updateBackRadar' -ErrorAction SilentlyContinue | Select-Object -Last 10 | ForEach-Object { $_.Line }
    }
    "carplay-start" {
        $out = Join-Path $PSScriptRoot "..\.unit-dump\carplay-$(Get-Date -Format yyyyMMdd-HHmm)-$Label"
        New-Item -ItemType Directory -Force $out | Out-Null
        Set-Content -Path (Join-Path $PSScriptRoot "..\.unit-dump\carplay-last.txt") -Value $out
        $before = foreach ($c in $carplayState) { Shell $base $c }
        $before | Out-File -Encoding utf8 (Join-Path $out "before.txt")
        $before
        # Jurnalul se scrie pe unitate: cand iPhone-ul intra pe Wi-Fi-ul adaptorului, hotspot-ul cade.
        Shell $base "pkill -f e92-carplay 2>/dev/null; rm -f /sdcard/e92-carplay.log*; logcat -c; nohup logcat -v time -f /sdcard/e92-carplay.log -r 8192 -n 3 >/dev/null 2>&1 & sleep 1; ls -la /sdcard/e92-carplay.log"
        Write-Output ""
        Write-Output "Jurnalul ruleaza pe unitate. Acum: scoate/baga modulul din USB daca vrei, porneste AutoPlay,"
        Write-Output "conecteaza iPhone-ul la CarPlay. Cand termini, porneste iar hotspot-ul si ruleaza carplay-fetch."
    }
    "carplay-fetch" {
        $last = Join-Path $PSScriptRoot "..\.unit-dump\carplay-last.txt"
        $out = if (Test-Path $last) { (Get-Content $last).Trim() } else { Join-Path $PSScriptRoot "..\.unit-dump\carplay-$(Get-Date -Format yyyyMMdd-HHmm)-$Label" }
        New-Item -ItemType Directory -Force $out | Out-Null
        $after = foreach ($c in $carplayState) { Shell $base $c }
        $after | Out-File -Encoding utf8 (Join-Path $out "after.txt")
        $after
        Shell $base "pkill -f e92-carplay 2>/dev/null; ls -la /sdcard/e92-carplay.log*" 
        # Doar liniile care conteaza, ca sa incapa intr-un raspuns.
        $pattern = 'manstep|autokit|autoplay|carplay|iap|usb|UsbHost|UsbDevice|wifi|wlan|p2p|bluetooth|bt_|ActivityManager: (Start|Displayed|Killing)|FATAL|AndroidRuntime'
        Shell $base "cat /sdcard/e92-carplay.log.3 /sdcard/e92-carplay.log.2 /sdcard/e92-carplay.log.1 /sdcard/e92-carplay.log 2>/dev/null | grep -i -E '$pattern' | tail -n 8000" |
            Out-File -Encoding utf8 (Join-Path $out "logcat-filtrat.txt")
        $lines = (Get-Content (Join-Path $out "logcat-filtrat.txt")).Count
        Write-Output "Jurnal adus: $lines linii in $out"
    }
    "scene-on" { Call $base "/sim?scene=1"; Start-Sleep 2; Call $base "/events?n=5" }
    "scene-off" { Call $base "/sim?scene=0"; Start-Sleep 2; Call $base "/events?n=5" }
    "grant" {
        # Deschide adb doar cat dureaza acordarea, apoi il inchide la loc.
        $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        $target = "$(([uri]$base).Host):5555"
        curl.exe -s --max-time 10 -H "X-Key: $Key" --data-binary "setprop service.adb.tcp.port 5555; setprop ctl.restart adbd" "$base/shell" | Out-Null
        Start-Sleep 4
        & $adb connect $target
        & $adb -s $target shell pm grant ro.e92.launcher android.permission.READ_LOGS
        $granted = & $adb -s $target shell "dumpsys package ro.e92.launcher | grep READ_LOGS"
        & $adb -s $target shell "setprop service.adb.tcp.port -1; setprop ctl.restart adbd" 2>$null
        & $adb disconnect $target | Out-Null
        if ("$granted" -match "granted=true") { Write-Output "OK: launcher-ul poate citi marsarierul." }
        else { Write-Output "ROSU: permisiunea nu s-a acordat: $granted"; exit 1 }
    }
    "reverse" {
        $end = (Get-Date).AddMinutes($Minutes)
        $seen = @{}
        Write-Output "Urmaresc $Minutes minute. Intra in marsarier si apropie-te incet de un obstacol."
        while ((Get-Date) -lt $end) {
            foreach ($line in @(Call $base "/events?n=80")) {
                if ("$line" -match "Reverse|scena de|radar KSW" -and -not $seen.ContainsKey("$line")) {
                    $seen["$line"] = $true
                    Write-Output "$line"
                }
            }
            Start-Sleep 2
        }
        if (($seen.Keys | Where-Object { $_ -match "radar KSW" }).Count -eq 0) {
            Write-Output "Nicio data de la senzori pe canalul KSW."
        }
    }
    "mcu" {
        # Setarile de marsarier ale placii (MCU): tip camera, radar, linii, video original.
        # -Label "camera=3" sau "camera=0&radar=0&track=0"; fara -Label arata ce e salvat.
        if ($Label -eq "test") { Call $base "/mcu" } else { Call $base "/mcu?$Label" }
    }
    "kswapps" {
        # Aplicatiile KSW de ecran care apar singure peste launcher: playerul de muzica, video,
        # DVD, DVR, AUX, TV. Dezactivare reversibila (pm disable-user): nu se sterge nimic,
        # "-Label on" le pune la loc. Raman neatinse: eventcenter (legatura cu masina),
        # settings si btsuite (EventCenter le porneste direct, fara protectie), fatset,
        # updatemcu, index (launcher de rezerva), ksw_dashboard, navigation, ksw_old_bmw_x1_original.
        $apps = @(
            "com.szchoiceway.musicplayer", "com.szchoiceway.videoplayer", "com.szchoiceway.dvdplayer",
            "com.szchoiceway.ksw_dvd", "com.szchoiceway.ksw_dvr", "com.szchoiceway.ksw_cmmb",
            "com.szchoiceway.ksw_aux"
        )
        if (@("off", "on", "status") -notcontains $Label) {
            Write-Output "Foloseste: car.ps1 kswapps -Label off | on | status"
            exit 1
        }
        if ($Label -ne "status") {
            # Deschide adb doar cat dureaza schimbarea, apoi il inchide la loc.
            $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
            $target = "$(([uri]$base).Host):5555"
            curl.exe -s --max-time 10 -H "X-Key: $Key" --data-binary "setprop service.adb.tcp.port 5555; setprop ctl.restart adbd" "$base/shell" | Out-Null
            Start-Sleep 4
            cmd /c "`"$adb`" connect $target" | Out-Null
            try {
                foreach ($app in $apps) {
                    if ($Label -eq "off") { cmd /c "`"$adb`" -s $target shell pm disable-user --user 0 $app" | Out-Null }
                    else { cmd /c "`"$adb`" -s $target shell pm enable $app" | Out-Null }
                }
            } finally {
                cmd /c "`"$adb`" -s $target shell `"setprop service.adb.tcp.port -1; setprop ctl.restart adbd`"" 2>$null
                cmd /c "`"$adb`" disconnect $target" | Out-Null
            }
            Start-Sleep 2
        }
        # Starea se citeste fara adb: lista pachetelor dezactivate o vede orice aplicatie.
        $disabled = "$(curl.exe -s --max-time 15 -H "X-Key: $Key" --data-binary "pm list packages -d" "$base/shell")"
        foreach ($app in $apps) {
            if ($disabled -match [regex]::Escape("package:$app")) { Write-Output "  dezactivata  $app" }
            else { Write-Output "  activa       $app" }
        }
    }
    "revert" {
        Call $base "/bt/engine?set=ksw"
        Start-Sleep 8
        $s = Read-Status $base
        Write-Output "motor=$($s.engine) ruleaza=$($s.running) tip=$($s.btType)"
        if ($s.engine -eq "ksw" -and $s.btType -ne "6") { Write-Output "OK: e din nou pe KSW." }
        else { Write-Output "ATENTIE: tipul a ramas $($s.btType). Vezi Factory > BT Module."; exit 1 }
    }
    "watch" {
        $end = (Get-Date).AddMinutes($Minutes)
        $last = ""
        $drops = 0
        $playSeconds = 0
        $wasConnected = $false
        Write-Output "Urmaresc $Minutes minute."
        while ((Get-Date) -lt $end) {
            $s = Read-Status $base
            if (-not $s.apk) {
                Write-Output "$(Get-Date -Format HH:mm:ss) unitatea nu raspunde"
                Start-Sleep 5
                continue
            }
            $connected = $s.audioConnected -eq "true"
            if ($wasConnected -and -not $connected) { $drops++ }
            $wasConnected = $connected
            if ($s.streaming -eq "true") { $playSeconds += 5 }
            $now = "telefon=$($s.phone) legat=$($s.audioConnected) canta=$($s.streaming) piesa=$($s.song) link=$($s.link)"
            if ($now -ne $last) {
                Write-Output "$(Get-Date -Format HH:mm:ss) $now"
                $last = $now
            }
            Start-Sleep 5
        }
        Write-Output "Rezultat: $drops caderi, a cantat aproximativ $playSeconds s din $($Minutes * 60) s."
        if ($drops -gt 0) { exit 1 }
    }
    "prepare" {
        if (-not (Test-Path $Apk)) { Write-Output "ROSU: lipseste $Apk"; exit 2 }
        $want = (Get-FileHash $Apk -Algorithm SHA256).Hash.ToLower()

        $s = Read-Status $base
        if ($s.apk -ne $want) {
            if (-not (Install-Latest $base $want)) {
                Write-Output "ROSU: versiunea noua nu ruleaza. Apasa Install pe unitate si ruleaza din nou."
                exit 2
            }
            # Procesul tocmai a pornit: lasa-l sa-si deschida legatura.
            Start-Sleep 5
        } else {
            Write-Output "Unitatea are deja ultima versiune."
        }

        $s = Read-Status $base
        if ($s.engine -ne "e92") {
            Write-Output "Pun motorul Bluetooth pe E92 (bTSuite elibereaza portul, cateva secunde)..."
            Call $base "/bt/engine?set=e92"
        }
        # Asteptam raspunsul stivei: numele unitatii vine din AT#MM.
        for ($i = 0; $i -lt 12; $i++) {
            Start-Sleep 3
            $s = Read-Status $base
            if ($s.running -eq "e92" -and $s.unitName) { break }
        }
        # Prima pornire scrie profilul si reporneste stiva; asteptam sa revina.
        for ($i = 0; $i -lt 15; $i++) {
            if ($s.profile -match '^160\b' -and $s.unitName) { break }
            Start-Sleep 3
            $s = Read-Status $base
        }

        $red = @()
        $yellow = @()
        function Check([bool] $ok, [string] $good, [string] $bad) {
            if ($ok) { Write-Output "  [OK] $good" }
            else { Write-Output "  [!!] $bad"; $script:red += $bad }
        }
        function Note([bool] $ok, [string] $good, [string] $bad) {
            if ($ok) { Write-Output "  [OK] $good" }
            else { Write-Output "  [ i] $bad"; $script:yellow += $bad }
        }

        Write-Output ""
        Write-Output "Verificari:"
        Check ($s.apk -eq $want) "ultima versiune instalata" "versiunea de pe unitate nu e ultima"
        Check ($s.running -eq "e92") "motorul E92 ruleaza" "motorul E92 nu ruleaza (running=$($s.running))"
        Check ($s.btType -eq "6") "bTSuite e pe portul liber (tip 6)" "tipul modulului e '$($s.btType)', trebuia 6"
        Check ($s.pty -eq "true") "stiva Bluetooth pornita, $($s.stack)" "lipseste /dev/goc_serial, $($s.stack)"
        Check ($s.link -ne "UNAVAILABLE") "legatura cu stiva e deschisa (raw)" "legatura cu stiva e inchisa"
        Check ([bool]$s.unitName) "stiva raspunde, unitatea se numeste '$($s.unitName)', PIN $($s.pin)" "stiva nu raspunde la comenzi"
        Check ($s.moduleSilent -ne "true") "stiva nu tace" "stiva a tacut"
        Check ($s.restartNeeded -ne "true") "bTSuite nu mai tine portul" "bTSuite inca tine portul: contact OPRIT si PORNIT, apoi ruleaza din nou"
        Check ($s.profile -match '^160\b') "stiva e doar pe muzica (profil 160)" "profilul stivei e '$($s.profile)', trebuia 160"
        Note ($s.modeMemory -eq "0") "reluarea ecranului KSW la pornire e oprita" "Sys_Mode_Memory=$($s.modeMemory)"
        Note ([bool]$s.pairedPhone) "telefon memorat: $($s.pairedPhone)" "stiva nu raporteaza niciun telefon memorat; poate fi nevoie de 'Connect new mobile'"

        Write-Output ""
        Write-Output "Ultimele evenimente:"
        Call $base "/events?n=12"

        Write-Output ""
        if ($red.Count -gt 0) {
            Write-Output "ROSU - nu te conecta inca:"
            $red | ForEach-Object { Write-Output "  - $_" }
            exit 1
        }
        Write-Output "VERDE - poti porni Bluetooth pe telefon."
        Write-Output "Profil Bluetooth: $($s.profile)"
        Write-Output "iPhone: Settings > Bluetooth > uita '$($s.unitName)', apoi asociaza din nou, PIN $($s.pin)."
        if ($s.audioConnected -eq "true") { Write-Output "Telefonul e deja legat: $($s.phone). Apasa play." }
        if ($yellow.Count -gt 0) { $yellow | ForEach-Object { Write-Output "  de retinut: $_" } }
    }
    default { Write-Output "prepare | status | watch | play | revert | btcapture | rprobe | grant | reverse | scene-on | scene-off | carplay-start | carplay-fetch" }
}
