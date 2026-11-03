<#
  Simulează pe emulator ce trimite unitatea KSW din mașină, ca să vezi
  ecranele launcher-ului fără mașină. Aceleași broadcast-uri, aceleași chei.

    .\scripts\sim.ps1 drive          viteză 87, 2300 rpm, 41 l, 512 km, 14.5°
    .\scripts\sim.ps1 park           mașina oprită, motor la ralanti
    .\scripts\sim.ps1 lowfuel        rezervă (45 km) și 2.5° afară
    .\scripts\sim.ps1 handbrake      frâna de mână trasă în mers
    .\scripts\sim.ps1 music "Titlu"  telefon conectat, cântă melodia dată
    .\scripts\sim.ps1 call           apel primit de la „Mama"
    .\scripts\sim.ps1 talk           convorbire în curs
    .\scripts\sim.ps1 hangup         apelul se termină
    .\scripts\sim.ps1 goodbye        contact oprit (ecranul de rămas-bun)
    .\scripts\sim.ps1 ignition       contact pornit
    .\scripts\sim.ps1 reverse        marșarier băgat / .\scripts\sim.ps1 forward
#>
param(
    [Parameter(Position = 0)] [string] $What = "help",
    [Parameter(Position = 1)] [string] $Arg = ""
)

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$car = "com.szchoiceway.eventcenter.EventUtils.ZXW_SENDBROADCAST8902MOD"
$bt = "com.szchoiceway.btsuite"
$ev = "com.choiceway.eventcenter.EventUtils"

function Send([string] $args1) { & $adb shell "am broadcast $args1" | Out-Null }

function CarData([int] $speed, [int] $rpm, [int] $fuel, [int] $range, [string] $temp) {
    # KSW repetă pachetul cât merge motorul; trimitem câteva ca să nu „expire".
    1..3 | ForEach-Object {
        Send "-a $car --ei ${car}_EXTRA 25 --ei ${car}_ShunShiSuDu $speed --ei ${car}_fadongjizhuansu $rpm --es ${car}_youLiang $fuel --es ${car}_xushilicheng ${range}km --es ${car}_huanjinwendu ${temp}C"
        Start-Sleep -Milliseconds 400
    }
}

switch ($What) {
    "drive"     { CarData 87 2300 41 512 "14.5" }
    "park"      { CarData 0 800 41 512 "14.5" }
    "lowfuel"   { CarData 0 800 6 45 "2.5" }
    "handbrake" {
        CarData 30 1500 41 512 "14.5"
        Send "-a $car --ei ${car}_EXTRA 16 --ez ${car}_shousha true --ez ${car}_anquandai true"
    }
    "music" {
        $title = if ($Arg) { $Arg } else { "Bohemian Rhapsody" }
        Send "-a $bt.HBCP_EVT_HSHF_STATUS --ei $bt.DATA_INT 3"
        Send "-a $bt.HBCP_EVT_AV_STATUS --ei $bt.DATA_INT 4 --es $bt.DATA_STR '$title'"
    }
    "call" {
        Send "-a $bt.HBCP_EVT_CONTACT_NUM --es $bt.DATA_STR +40722123456"
        Send "-a $bt.HBCP_EVT_CONTACT_ADDRESS --es $bt.DATA_STR Mama"
        Send "-a $bt.HBCP_EVT_HSHF_STATUS --ei $bt.DATA_INT 5"
    }
    "talk"      { Send "-a $bt.HBCP_EVT_HSHF_STATUS --ei $bt.DATA_INT 6" }
    "hangup"    { Send "-a $bt.HBCP_EVT_HSHF_STATUS --ei $bt.DATA_INT 3" }
    "goodbye"   { Send "-a $ev.KSW_ZXW_ACC_CONTROL_BEFORE --ez $ev.KSW_ZXW_ACC_CONTROL_BEFORE_DATA true" }
    "ignition"  { Send "-a $ev.KSW_ZXW_ACC_CONTROL_BEFORE --ez $ev.KSW_ZXW_ACC_CONTROL_BEFORE_DATA false" }
    "reverse"   { Send "-a $ev.MCU_MSG_BACKCAR_START" }
    "forward"   { Send "-a $ev.MCU_MSG_BACKCAR_END" }
    default     { Get-Content $PSCommandPath -TotalCount 18 | Select-Object -Skip 1 }
}
