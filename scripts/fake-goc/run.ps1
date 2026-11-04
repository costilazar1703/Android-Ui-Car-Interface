<#
  Pornește stiva Feasycom falsă pe emulator (o imagine google_apis, cu adb root).
  Launcher-ul o vede la fel ca în mașină: /dev/goc_serial e un pty real.

    .\scripts\fake-goc\run.ps1            compilează și pornește stiva
    .\scripts\fake-goc\run.ps1 drop       comandă telefonul (vezi FakeGoc.java)
    .\scripts\fake-goc\run.ps1 log        traficul de până acum
    .\scripts\fake-goc\run.ps1 events     jurnalul launcher-ului
    .\scripts\fake-goc\run.ps1 check      FORBIDDEN / ECHO în trafic = test picat
#>
param(
    [Parameter(Position = 0)] [string] $What = "start",
    [string] $Serial = ""
)

$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$flags = if ($Serial) { @("-s", $Serial) } else { @() }
function Adb { & $adb @flags @args }

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$remoteDex = "/data/local/tmp/fake-goc.dex"
$remoteLog = "/data/local/tmp/fake-goc.log"
$remoteCtl = "/data/local/tmp/fake-goc.ctl"

switch ($What) {
    "start" {
        $work = Join-Path ([IO.Path]::GetTempPath()) "fake-goc"
        Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force (Join-Path $work "classes") | Out-Null

        $jar = Join-Path $sdk "platforms\android-34\android.jar"
        $env:JAVA_HOME = Join-Path $env:USERPROFILE ".jdks\jbr-21.0.11"
        $javac = Join-Path $env:JAVA_HOME "bin\javac.exe"
        & $javac --release 8 -nowarn -cp $jar -d (Join-Path $work "classes") (Join-Path $here "FakeGoc.java")
        if ($LASTEXITCODE) { throw "javac" }
        # Direct din jar: d8.bat strica argumentele cu C:
        $d8Jar = Join-Path $sdk "build-tools\34.0.0\lib\d8.jar"
        $java = Join-Path $env:JAVA_HOME "bin\java.exe"
        $classes = @(Get-ChildItem (Join-Path $work "classes") -Filter *.class | ForEach-Object { $_.FullName })
        & $java -cp $d8Jar com.android.tools.r8.D8 --min-api 27 --lib $jar --output $work @classes
        if ($LASTEXITCODE) { throw "d8" }

        Adb root | Out-Null
        Start-Sleep -Seconds 2
        Adb wait-for-device
        # Ca pe unitate, unde SELinux e permisiv.
        Adb shell setenforce 0
        Adb push (Join-Path $work "classes.dex") $remoteDex | Out-Null
        Adb shell "pkill -f FakeGoc; rm -f /dev/goc_serial"
        Adb shell "CLASSPATH=$remoteDex nohup app_process / FakeGoc > /dev/null 2>&1 &"
        Start-Sleep -Seconds 2
        Adb shell "ls -l /dev/goc_serial; cat $remoteLog"
    }
    "btsuite" {
        # bTSuite fals: testeaza scrierea tipului 6 si repornirea BTService.
        $work = Join-Path ([IO.Path]::GetTempPath()) "fake-btsuite"
        Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force (Join-Path $work "classes") | Out-Null
        $src = Join-Path $here "fake-btsuite"
        $tools = Join-Path $sdk "build-tools\34.0.0"
        $jar = Join-Path $sdk "platforms\android-34\android.jar"
        $env:JAVA_HOME = Join-Path $env:USERPROFILE ".jdks\jbr-21.0.11"
        $java = Join-Path $env:JAVA_HOME "bin\java.exe"
        & (Join-Path $env:JAVA_HOME "bin\javac.exe") --release 8 -nowarn -cp $jar -d (Join-Path $work "classes") `
            (Join-Path $src "BTService.java") (Join-Path $src "SysVarProvider.java")
        if ($LASTEXITCODE) { throw "javac" }
        $classes = @(Get-ChildItem (Join-Path $work "classes") -Recurse -Filter *.class | ForEach-Object { $_.FullName })
        & $java -cp (Join-Path $tools "lib\d8.jar") com.android.tools.r8.D8 --min-api 27 --lib $jar --output $work @classes
        if ($LASTEXITCODE) { throw "d8" }
        $apk = Join-Path $work "fake-btsuite.apk"
        & (Join-Path $tools "aapt2.exe") link -o $apk -I $jar --manifest (Join-Path $src "AndroidManifest.xml") --min-sdk-version 27 --target-sdk-version 27
        if ($LASTEXITCODE) { throw "aapt2" }
        Push-Location $work
        & (Join-Path $tools "aapt.exe") add $apk classes.dex | Out-Null
        Pop-Location
        & $java -jar (Join-Path $tools "lib\apksigner.jar") sign --ks (Join-Path $env:USERPROFILE ".android\debug.keystore") --ks-pass pass:android $apk
        if ($LASTEXITCODE) { throw "apksigner" }
        Adb install -r $apk
    }
    "log" { Adb shell "cat $remoteLog" }
    "events" { Adb shell "cat /data/data/ro.e92.launcher/files/events.log" }
    "check" {
        $bad = Adb shell "grep -E 'FORBIDDEN|ECHO' $remoteLog"
        if ($bad) { Write-Output $bad; throw "trafic interzis" }
        Write-Output "OK: no HFP/phonebook commands, no echo"
        exit 0
    }
    default { Adb shell "echo $What > $remoteCtl" }
}
