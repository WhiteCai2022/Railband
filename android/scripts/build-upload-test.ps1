param(
    [string]$SdkRoot = 'D:\Android\SDK',
    [string]$JavaHome = 'D:\Android\Android Studio\jbr',
    [string]$AppClasses = '',
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe')) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"
}
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $AppClasses) { $AppClasses = Join-Path $root 'android\build\local-v07\classes.jar' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $root 'android\build\upload-test' }
$tools = Join-Path $SdkRoot 'build-tools\36.0.0'
$platform = Join-Path $SdkRoot 'platforms\android-37.0\android.jar'
New-Item -ItemType Directory -Force -Path "$OutputDirectory\classes","$OutputDirectory\dex" | Out-Null
$manifest = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="cn.railband.mvp.uploadtest"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36"/><application android:label="Upload scheduling regression"/><instrumentation android:name="cn.railband.mvp.UploadSchedulingTest" android:targetPackage="cn.railband.mvp.radio" android:functionalTest="true"/></manifest>'
Set-Content -LiteralPath "$OutputDirectory\AndroidManifest.xml" -Value $manifest -Encoding UTF8
& javac -encoding UTF-8 -source 8 -target 8 -classpath "$platform;$AppClasses" -d "$OutputDirectory\classes" "$PSScriptRoot\UploadSchedulingTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Upload test compilation failed' }
& jar cf "$OutputDirectory\classes.jar" -C "$OutputDirectory\classes" .
if ($LASTEXITCODE -ne 0) { throw 'Upload test jar failed' }
& "$tools\d8.bat" --lib $platform --classpath $AppClasses --min-api 28 --output "$OutputDirectory\dex" "$OutputDirectory\classes.jar"
if ($LASTEXITCODE -ne 0) { throw 'Upload test dex failed' }
& "$tools\aapt2.exe" link -o "$OutputDirectory\unsigned.apk" --manifest "$OutputDirectory\AndroidManifest.xml" -I $platform
if ($LASTEXITCODE -ne 0) { throw 'Upload test package failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open("$OutputDirectory\unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,"$OutputDirectory\dex\classes.dex",'classes.dex') | Out-Null } finally { $archive.Dispose() }
& "$tools\zipalign.exe" -f 4 "$OutputDirectory\unsigned.apk" "$OutputDirectory\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Upload test alignment failed' }
& "$tools\apksigner.bat" sign --ks D:\NSFC\tools\android\rail-radio-debug.jks --ks-pass pass:android --out "$OutputDirectory\upload-test.apk" "$OutputDirectory\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Upload test signing failed' }
Write-Output "$OutputDirectory\upload-test.apk"
