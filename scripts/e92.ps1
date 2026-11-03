<#
  Clientul de pe laptop pentru DiagServer - "adb-ul" unității din mașină.

  Unitatea RK3399 n-are adb: USB-ul e doar host, iar adb pe Wi-Fi e refuzat
  fără root. Launcher-ul ascultă el pe portul 8092; adresa și cheia le vezi
  sus pe ecranul Diagnostic. Laptopul și unitatea pe același hotspot.

  Prima dată:   .\scripts\e92.ps1 -Ip 172.20.10.9 -Key 1234 info
  Apoi (ține minte ip/cheia):
    .\scripts\e92.ps1 info
    .\scripts\e92.ps1 keys
    .\scripts\e92.ps1 logcat 300
    .\scripts\e92.ps1 shell "getprop | grep -i mcu"
    .\scripts\e92.ps1 sniff "com.mcu.CAN_INFO,android.intent.action.CAN_DATA"
    .\scripts\e92.ps1 sniff
    .\scripts\e92.ps1 screenshot ecran.png
    .\scripts\e92.ps1 install                  # app\build\outputs\apk\release\app-release.apk
#>
param(
    [Parameter(Position = 0)] [string] $Command = "help",
    [Parameter(Position = 1)] [string] $Arg = "",
    [string] $Ip,
    [string] $Key
)

$ErrorActionPreference = "Stop"
$configFile = Join-Path $env:LOCALAPPDATA "e92diag.json"
$config = @{ ip = "172.20.10.9"; key = "" }
if (Test-Path $configFile) {
    $saved = Get-Content $configFile -Raw | ConvertFrom-Json
    $config.ip = $saved.ip
    $config.key = $saved.key
}
if ($Ip) { $config.ip = $Ip }
if ($Key) { $config.key = $Key }
($config | ConvertTo-Json) | Out-File -Encoding utf8 $configFile

if (-not $config.key) { throw "Lipsește cheia: o vezi pe ecranul Diagnostic. Rulează cu -Key 1234 o dată." }

$base = "http://$($config.ip):8092"
$auth = "X-Key: $($config.key)"

function Get-Text([string] $path) {
    curl.exe -s --max-time 30 -H $auth "$base$path"
    if ($LASTEXITCODE -ne 0) { throw "Unitatea nu răspunde la $base (curl $LASTEXITCODE). E pe hotspot și launcher-ul pornit?" }
}

switch ($Command) {
    "info"   { Get-Text "/info" }
    "keys"   { Get-Text "/keys" }
    "events" { $n = if ($Arg) { $Arg } else { 400 }; Get-Text "/events?n=$n" }
    "logcat" { $n = if ($Arg) { $Arg } else { 500 }; Get-Text "/logcat?n=$n" }
    "shell"  {
        $tmp = New-TemporaryFile
        [IO.File]::WriteAllText($tmp, $Arg)
        curl.exe -s --max-time 40 -H $auth --data-binary "@$tmp" "$base/shell"
        Remove-Item $tmp
    }
    "sniff"  {
        if ($Arg) { Get-Text ("/sniff?actions=" + [uri]::EscapeDataString($Arg)) } else { Get-Text "/sniff" }
    }
    "screenshot" {
        $out = if ($Arg) { $Arg } else { "e92-screen.png" }
        curl.exe -s --max-time 30 -H $auth -o $out -w "%{http_code}" "$base/screenshot"
        Write-Host "  -> $out"
    }
    "install" {
        $apk = if ($Arg) { $Arg } else { Join-Path $PSScriptRoot "..\app\build\outputs\apk\release\app-release.apk" }
        curl.exe -s --max-time 300 -H $auth -H "Content-Type: application/octet-stream" --data-binary "@$apk" "$base/install"
    }
    default  { Get-Text "/help" }
}
