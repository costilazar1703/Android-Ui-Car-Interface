<#
  Ziua de masina: laptop si unitate pe hotspot-ul iPhone-ului. Scriptul gaseste
  unitatea, instaleaza ultima versiune, pune motorul Bluetooth E92, verifica tot
  si da VERDE sau ROSU. Poate fi rulat de cate ori e nevoie.

    .\scripts\car.ps1 prepare              tot, pana la verdict
    .\scripts\car.ps1 status               starea Bluetooth
    .\scripts\car.ps1 watch -Minutes 10    urmareste muzica si numara caderile
    .\scripts\car.ps1 play                 play/pauza de pe laptop
    .\scripts\car.ps1 revert               inapoi pe motorul KSW

  Fara diacritice: PowerShell 5.1 citeste scripturile fara BOM ca ANSI.
#>
param(
    [Parameter(Position = 0)] [string] $Command = "prepare",
    [string] $Ip = "",
    [string] $Key = "6543",
    [string] $Apk = "",
    [int] $Minutes = 10
)

$configFile = Join-Path $env:LOCALAPPDATA "e92diag.json"
if (-not $Apk) { $Apk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\release\app-release.apk" }

function Call([string] $base, [string] $path) {
    curl.exe -s --connect-timeout 2 --max-time 20 -H "X-Key: $Key" "$base$path"
}

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
        Write-Output "iPhone: Settings > Bluetooth > $($s.unitName) > Calls (Apeluri) OPRIT, PIN $($s.pin)."
        if ($s.audioConnected -eq "true") { Write-Output "Telefonul e deja legat: $($s.phone). Apasa play." }
        if ($yellow.Count -gt 0) { $yellow | ForEach-Object { Write-Output "  de retinut: $_" } }
    }
    default { Write-Output "prepare | status | watch | play | revert" }
}
