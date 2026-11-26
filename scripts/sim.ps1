<#
  E92 Launcher - car simulator for the Android emulator.

  Sends the launcher the same data the head unit sends in the car, so every screen
  can be tested without a car. See docs/emulator-testing.md for the full guide.

  Gear lever and driving modes (CAN, through the launcher's diagnostics server):
    .\scripts\sim.ps1 gear P|R|N|D       gear lever position
    .\scripts\sim.ps1 sport on|off       Drive Sport (lever in D)
    .\scripts\sim.ps1 manual 1-6|off     manual mode, gear 1 to 6
    .\scripts\sim.ps1 drift on|off       traction control off (Drift Mode) / back on
    .\scripts\sim.ps1 scene NAME         play one animation: drift, normal, sport, drive, park, neutral, manual
    .\scripts\sim.ps1 demo               P -> D -> Sport -> M1..M3 -> D -> P, then Drift Mode on and off

  Reverse:
    .\scripts\sim.ps1 reverse [cm] [steer]  reverse with 4 rear sensor distances, e.g. "150,40,18,60",
                                            steering -10400 (full one way) .. 10400 (full the other way)
    .\scripts\sim.ps1 forward               leave reverse

  Car data (speed, rpm, fuel, range, outside temperature, handbrake):
    .\scripts\sim.ps1 data-drive         87 km/h, 2300 rpm, 41 l, 512 km, 14.5 C
    .\scripts\sim.ps1 data-park          stopped, engine idling
    .\scripts\sim.ps1 lowfuel            6 l, 45 km range, 2.5 C outside
    .\scripts\sim.ps1 handbrake          handbrake pulled while moving

  Phone and media (head unit Bluetooth):
    .\scripts\sim.ps1 music "Title"      phone connected, playing the given song
    .\scripts\sim.ps1 call               incoming call
    .\scripts\sim.ps1 talk               call in progress
    .\scripts\sim.ps1 hangup             call ended

  Unit:
    .\scripts\sim.ps1 ignition off|on    key turned off (ends the current trip) / on again
    .\scripts\sim.ps1 wifi 0-3|off|real  Wi-Fi signal shown in the status bar
    .\scripts\sim.ps1 vol up|down        steering wheel volume buttons

  The diagnostics server needs a key. The script reads it from the emulator (adb root,
  works on "Google APIs" system images). Otherwise read it in the launcher at
  CAR > Diagnostics (first line, "cheie NNNN") and pass it once: -Key NNNN.
#>
param(
    [Parameter(Position = 0)] [string] $What = "help",
    [Parameter(Position = 1)] [string] $Arg = "",
    [Parameter(Position = 2)] [string] $Arg2 = "",
    [string] $Key = $env:E92_KEY
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$car = "com.szchoiceway.eventcenter.EventUtils.ZXW_SENDBROADCAST8902MOD"
$bt = "com.szchoiceway.btsuite"
$ev = "com.choiceway.eventcenter.EventUtils"
$port = 8092

function Send([string] $args1) { & $adb shell "am broadcast $args1" | Out-Null }

function Get-DiagKey {
    if ($script:Key) { return $script:Key }
    & $adb root 2>$null | Out-Null
    Start-Sleep -Milliseconds 800
    $xml = & $adb shell "cat /data/data/ro.e92.launcher/shared_prefs/*.xml" 2>$null
    $m = [regex]::Match(($xml -join "`n"), '<string name="key">(\d+)</string>')
    if (-not $m.Success) {
        throw "No diagnostics key. Open CAR > Diagnostics in the launcher, read the 4 digits after 'cheie' and run again with -Key NNNN."
    }
    $script:Key = $m.Groups[1].Value
    return $script:Key
}

function Diag([string] $path) {
    & $adb forward "tcp:$port" "tcp:$port" | Out-Null
    $k = Get-DiagKey
    $r = Invoke-WebRequest -UseBasicParsing -Headers @{ "X-Key" = $k } "http://127.0.0.1:$port$path"
    Write-Output ("{0}  {1}" -f $r.Content.Trim(), $path)
}

function Sim([string] $query) { Diag "/sim?$query" }

function OnOff([string] $value) {
    switch ($value) { "on" { "1" } "1" { "1" } "off" { "0" } "0" { "0" } default { throw "use on or off" } }
}

function CarData([int] $speed, [int] $rpm, [int] $fuel, [int] $range, [string] $temp) {
    # The unit repeats this packet while the engine runs; send a few so it does not expire.
    1..3 | ForEach-Object {
        Send "-a $car --ei ${car}_EXTRA 25 --ei ${car}_ShunShiSuDu $speed --ei ${car}_fadongjizhuansu $rpm --es ${car}_youLiang $fuel --es ${car}_xushilicheng ${range}km --es ${car}_huanjinwendu ${temp}C"
        Start-Sleep -Milliseconds 400
    }
}

switch ($What) {
    "gear" {
        $g = $Arg.ToUpper()
        if ($g -notin "P", "R", "N", "D") { throw "gear P, R, N or D" }
        Sim "lever=$g"
    }
    "sport"  { Sim "sport=$(OnOff $Arg)" }
    "manual" { Sim "manual=$(if ($Arg -eq 'off') { 0 } else { [int]$Arg })" }
    "drift"  { Sim "drift=$(OnOff $Arg)" }
    "scene"  { Sim "mode=$Arg" }
    "demo" {
        # Like in the car, the first value after the launcher starts is only a starting
        # point (no animation), so the demo sets P first.
        Sim "manual=0&sport=0&lever=P&drift=0"; Start-Sleep 3
        Sim "lever=D";   Start-Sleep 4
        Sim "sport=1";   Start-Sleep 4
        Sim "manual=1";  Start-Sleep 4
        Sim "manual=2";  Start-Sleep 3
        Sim "manual=3";  Start-Sleep 3
        Sim "sport=0";   Start-Sleep 4
        Sim "lever=P";   Start-Sleep 4
        Sim "drift=1";   Start-Sleep 6
        Sim "drift=0"
    }
    "reverse" {
        $cm = if ($Arg) { $Arg } else { "150,40,18,60" }
        $steer = if ($Arg2) { "&steer=$Arg2" } else { "" }
        Sim "scene=1&reverse=1&cm=$cm$steer"
    }
    "forward"    { Sim "reverse=0&steer=0" }
    "wifi"       { Sim "wifi=$Arg" }
    "vol"        { Sim "vol=$Arg" }
    "data-drive" { CarData 87 2300 41 512 "14.5" }
    "data-park"  { CarData 0 800 41 512 "14.5" }
    "lowfuel"    { CarData 0 800 6 45 "2.5" }
    "handbrake" {
        CarData 30 1500 41 512 "14.5"
        Send "-a $car --ei ${car}_EXTRA 16 --ez ${car}_shousha true --ez ${car}_anquandai true"
    }
    "music" {
        $title = if ($Arg) { $Arg } else { "Demo Song" }
        Send "-a $bt.HBCP_EVT_HSHF_STATUS --ei $bt.DATA_INT 3"
        Send "-a $bt.HBCP_EVT_AV_STATUS --ei $bt.DATA_INT 4 --es $bt.DATA_STR '$title'"
    }
    "call" {
        Send "-a $bt.HBCP_EVT_CONTACT_NUM --es $bt.DATA_STR 0700000000"
        Send "-a $bt.HBCP_EVT_CONTACT_ADDRESS --es $bt.DATA_STR 'Demo Contact'"
        Send "-a $bt.HBCP_EVT_HSHF_STATUS --ei $bt.DATA_INT 5"
    }
    "talk"     { Send "-a $bt.HBCP_EVT_HSHF_STATUS --ei $bt.DATA_INT 6" }
    "hangup"   { Send "-a $bt.HBCP_EVT_HSHF_STATUS --ei $bt.DATA_INT 3" }
    "ignition" {
        $off = if ($Arg -eq "off") { "true" } elseif ($Arg -eq "on") { "false" } else { throw "ignition off or on" }
        Send "-a $ev.KSW_ZXW_ACC_CONTROL_BEFORE --ez $ev.KSW_ZXW_ACC_CONTROL_BEFORE_DATA $off"
    }
    default    { Get-Content $PSCommandPath -TotalCount 39 | Select-Object -Skip 1 }
}
