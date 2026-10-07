param(
    [string]$Sdk = '',
    [string]$BuildTools = '35.0.0',
    [switch]$TestsOnly
)
$ErrorActionPreference = 'Stop'
if (-not $Sdk) {
    if ($env:ANDROID_HOME) { $Sdk = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $Sdk = $env:ANDROID_SDK_ROOT }
    else { $Sdk = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'AppData\Local\Android\Sdk' }
}
$projectDir = $PSScriptRoot
$buildDir = Join-Path $projectDir 'build'
$tempDir = Join-Path $buildDir 'temp'
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
$env:TEMP = $tempDir
$env:TMP = $tempDir
$testDir = Join-Path $buildDir 'tests'
New-Item -ItemType Directory -Force -Path $testDir | Out-Null
$coreSource = Join-Path $projectDir 'core\src\main\java\org\pocketrelay\core\RelayCore.java'
$testSource = Join-Path $projectDir 'core\src\test\java\org\pocketrelay\core\RelayCoreTest.java'
& javac --release 8 -encoding UTF-8 -d $testDir $coreSource $testSource
if ($LASTEXITCODE -ne 0) { throw 'Protocol test compilation failed' }
& java -cp $testDir org.pocketrelay.core.RelayCoreTest
if ($LASTEXITCODE -ne 0) { throw 'Protocol checks failed' }
if ($TestsOnly) { return }
$androidJar = Join-Path $Sdk 'platforms\android-35\android.jar'
$toolsDir = Join-Path $Sdk "build-tools\$BuildTools"
foreach ($required in @($androidJar, (Join-Path $toolsDir 'aapt2.exe'), (Join-Path $toolsDir 'd8.bat'), (Join-Path $toolsDir 'apksigner.bat'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing SDK tool: $required. Supply -Sdk with your Android SDK location." }
}
$compiledDir = Join-Path $buildDir 'resources'
$classesDir = Join-Path $buildDir 'classes'
$dexDir = Join-Path $buildDir 'dex'
$distDir = Join-Path $projectDir 'dist'
New-Item -ItemType Directory -Force -Path $compiledDir,$classesDir,$dexDir,$distDir | Out-Null
& (Join-Path $toolsDir 'aapt2.exe') compile --dir (Join-Path $projectDir 'app\src\main\res') -o $compiledDir
if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed' }
$unsigned = Join-Path $buildDir 'unsigned.apk'
$resourceFiles = @(Get-ChildItem -LiteralPath $compiledDir -Filter '*.flat' | ForEach-Object FullName)
& (Join-Path $toolsDir 'aapt2.exe') link -o $unsigned -I $androidJar --manifest (Join-Path $projectDir 'app\src\main\AndroidManifest.xml') --min-sdk-version 26 --target-sdk-version 35 @resourceFiles
if ($LASTEXITCODE -ne 0) {
    Write-Output 'Resource tool failed; checking whether all compiled entries are intact.'
    & python (Join-Path $projectDir 'tools\apk_archive.py') recover $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'Resource linking failed and archive recovery was not safe' }
}
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectDir 'app\src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac --release 8 -encoding UTF-8 -cp $androidJar -d $classesDir $coreSource @sources
if ($LASTEXITCODE -ne 0) { throw 'Android compilation failed' }
$classesJar = Join-Path $buildDir 'classes.jar'
& jar "-J-Djava.io.tmpdir=$tempDir" cf $classesJar -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw 'Class packaging failed' }
& (Join-Path $toolsDir 'd8.bat') --min-api 26 --lib $androidJar --output $dexDir $classesJar
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($unsigned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $existing = $archive.GetEntry('classes.dex')
    if ($null -ne $existing) { $existing.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $dexDir 'classes.dex'), 'classes.dex', [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally { $archive.Dispose() }
$aligned = Join-Path $buildDir 'aligned.apk'
& python (Join-Path $projectDir 'tools\apk_archive.py') align $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
$key = Join-Path $buildDir 'local-test.keystore'
if (-not (Test-Path -LiteralPath $key)) {
    & keytool -genkeypair -keystore $key -storepass android -keypass android -alias pocketrelay -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Pocket Relay Local Test'
    if ($LASTEXITCODE -ne 0) { throw 'Local test signing key generation failed' }
}
$apk = Join-Path $distDir 'PocketRelay-0.1.0.apk'
& (Join-Path $toolsDir 'apksigner.bat') sign --ks $key --ks-key-alias pocketrelay --ks-pass pass:android --key-pass pass:android --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
& (Join-Path $toolsDir 'apksigner.bat') verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
Write-Output "Built and verified: $apk"
