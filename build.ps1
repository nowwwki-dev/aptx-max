param(
    [switch]$SkipReleaseCopy
)

$ErrorActionPreference = "Stop"

function Resolve-FirstExistingFile {
    param([string[]]$Paths)
    foreach ($path in $Paths) {
        if ($path -and (Test-Path -LiteralPath $path -PathType Leaf)) {
            return (Resolve-Path -LiteralPath $path).Path
        }
    }
    throw "File not found: $($Paths -join ', ')"
}

function Resolve-FirstExistingDirectory {
    param([string[]]$Paths)
    foreach ($path in $Paths) {
        if ($path -and (Test-Path -LiteralPath $path -PathType Container)) {
            return (Resolve-Path -LiteralPath $path).Path
        }
    }
    throw "Directory not found: $($Paths -join ', ')"
}

function New-CleanDirectory {
    param([string]$Path)
    Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

function Assert-LastExitCode {
    param([string]$Step)
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$App = Join-Path $Root "AptxMaxApp"
$Build = Join-Path $Root "build"
$Release = Join-Path $Root "releases"

$JavaHome = Resolve-FirstExistingDirectory @(
    $env:JAVA_HOME,
    (Join-Path $env:ProgramFiles "Android\Android Studio\jbr")
)
$env:JAVA_HOME = $JavaHome
$env:Path = (Join-Path $JavaHome "bin") + ";" + $env:Path

$Javac = Resolve-FirstExistingFile @((Join-Path $JavaHome "bin\javac.exe"))
$Jar = Resolve-FirstExistingFile @((Join-Path $JavaHome "bin\jar.exe"))
$Keytool = Resolve-FirstExistingFile @((Join-Path $JavaHome "bin\keytool.exe"))

$SdkRoot = Resolve-FirstExistingDirectory @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    (Join-Path $env:LOCALAPPDATA "Android\Sdk")
)

$PlatformCandidates = @(Join-Path $SdkRoot "platforms\android-36.1")
$PlatformCandidates += Get-ChildItem -LiteralPath (Join-Path $SdkRoot "platforms") -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    ForEach-Object { $_.FullName }
$Platform = Resolve-FirstExistingDirectory $PlatformCandidates
$AndroidJar = Resolve-FirstExistingFile @((Join-Path $Platform "android.jar"))

$BuildTools = Get-ChildItem -LiteralPath (Join-Path $SdkRoot "build-tools") -Directory |
    Sort-Object Name -Descending |
    Where-Object {
        (Test-Path -LiteralPath (Join-Path $_.FullName "aapt2.exe")) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName "d8.bat")) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName "zipalign.exe")) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName "apksigner.bat"))
    } |
    Select-Object -First 1
if (-not $BuildTools) {
    throw "Android Build Tools with aapt2/d8/zipalign/apksigner were not found."
}

$Aapt2 = Join-Path $BuildTools.FullName "aapt2.exe"
$D8 = Join-Path $BuildTools.FullName "d8.bat"
$Zipalign = Join-Path $BuildTools.FullName "zipalign.exe"
$Apksigner = Join-Path $BuildTools.FullName "apksigner.bat"

New-CleanDirectory $Build
New-Item -ItemType Directory -Force -Path $Release | Out-Null

$Classes = Join-Path $Build "classes"
$HelperClasses = Join-Path $Build "helper-classes"
$Generated = Join-Path $Build "generated"
$DexOut = Join-Path $Build "dex"
$HelperDexOut = Join-Path $Build "helper-dex"
$ResCompiled = Join-Path $Build "res-compiled"
$ExplodedLibs = Join-Path $Build "exploded-libs"

New-Item -ItemType Directory -Force -Path $Classes,$HelperClasses,$Generated,$DexOut,$HelperDexOut,$ResCompiled,$ExplodedLibs | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem

$CompileClasspath = New-Object System.Collections.Generic.List[string]
$D8ProgramJars = New-Object System.Collections.Generic.List[string]
$CompileClasspath.Add($AndroidJar)

$AnnotationJar = Join-Path $App "libs\androidx-annotation-1.3.0.jar"
if (Test-Path -LiteralPath $AnnotationJar) {
    $CompileClasspath.Add((Resolve-Path -LiteralPath $AnnotationJar).Path)
}

Get-ChildItem -LiteralPath (Join-Path $App "libs") -Filter "*.aar" | ForEach-Object {
    $Name = [System.IO.Path]::GetFileNameWithoutExtension($_.Name)
    $Out = Join-Path $ExplodedLibs $Name
    New-CleanDirectory $Out
    [System.IO.Compression.ZipFile]::ExtractToDirectory($_.FullName, $Out)
    $ClassesJar = Join-Path $Out "classes.jar"
    if (Test-Path -LiteralPath $ClassesJar) {
        $CompileClasspath.Add($ClassesJar)
        $D8ProgramJars.Add($ClassesJar)
    }
}

& $Aapt2 compile --legacy -o $ResCompiled (Join-Path $App "res\values\styles.xml")
Assert-LastExitCode "aapt2 compile"

$UnsignedApk = Join-Path $Build "aptx-max-unsigned.apk"
$LinkArgs = @(
    "link",
    "-o", $UnsignedApk,
    "-I", $AndroidJar,
    "--manifest", (Join-Path $App "AndroidManifest.xml"),
    "--min-sdk-version", "36",
    "--target-sdk-version", "36",
    "--auto-add-overlay",
    "--java", $Generated
)
Get-ChildItem -LiteralPath $ResCompiled -Filter "*.flat" | ForEach-Object {
    $LinkArgs += @("-R", $_.FullName)
}
& $Aapt2 @LinkArgs
Assert-LastExitCode "aapt2 link"

$SourceFiles = Get-ChildItem -LiteralPath (Join-Path $App "src") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }
$ClassPath = ($CompileClasspath.ToArray() -join ";")
& $Javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $AndroidJar -classpath $ClassPath -d $Classes $SourceFiles
Assert-LastExitCode "javac app"

$AppJar = Join-Path $Build "app-classes.jar"
Push-Location $Classes
& $Jar cf $AppJar .
Assert-LastExitCode "jar app"
Pop-Location

$D8Inputs = @($AppJar) + $D8ProgramJars.ToArray()
& $D8 --lib $AndroidJar --output $DexOut $D8Inputs
Assert-LastExitCode "d8 app"

$HelperSource = Join-Path $Root "scripts\SetA2dpCodec.java"
& $Javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $AndroidJar -classpath $AndroidJar -d $HelperClasses $HelperSource
Assert-LastExitCode "javac helper"

$HelperJar = Join-Path $Build "set-a2dp-codec.jar"
Push-Location $HelperClasses
& $Jar cf $HelperJar .
Assert-LastExitCode "jar helper"
Pop-Location
& $D8 --lib $AndroidJar --output $HelperDexOut $HelperJar
Assert-LastExitCode "d8 helper"
Copy-Item -Force -Path (Join-Path $HelperDexOut "classes.dex") -Destination (Join-Path $Build "set-a2dp-codec.dex")

$WithDex = Join-Path $Build "aptx-max-with-dex.apk"
Copy-Item -Force -Path $UnsignedApk -Destination $WithDex
Push-Location $DexOut
& $Jar uf $WithDex "classes.dex"
Assert-LastExitCode "jar update apk"
Pop-Location

$Aligned = Join-Path $Build "aptx-max-aligned.apk"
$Signed = Join-Path $Build "aptx-max.apk"
& $Zipalign -f -p 4 $WithDex $Aligned
Assert-LastExitCode "zipalign"

$Signing = Join-Path $Root "signing"
New-Item -ItemType Directory -Force -Path $Signing | Out-Null
$Keystore = Join-Path $Signing "debug.keystore"
if (-not (Test-Path -LiteralPath $Keystore)) {
    & $Keytool -genkeypair -v `
        -keystore $Keystore `
        -storepass android `
        -alias androiddebugkey `
        -keypass android `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" | Out-Null
    Assert-LastExitCode "keytool"
}

& $Apksigner sign `
    --ks $Keystore `
    --ks-pass pass:android `
    --ks-key-alias androiddebugkey `
    --key-pass pass:android `
    --out $Signed `
    $Aligned
Assert-LastExitCode "apksigner sign"

& $Apksigner verify --verbose $Signed
Assert-LastExitCode "apksigner verify"

if (-not $SkipReleaseCopy) {
    Copy-Item -Force -Path $Signed -Destination (Join-Path $Release "aptx-max.apk")
}

Write-Host "APK: $Signed"
Write-Host "Helper DEX: $(Join-Path $Build 'set-a2dp-codec.dex')"
