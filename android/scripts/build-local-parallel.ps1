param(
    [string]$SdkRoot = 'D:\Android\SDK',
    [string]$PreviousApk = 'D:\NSFC\output\apk\rail-bandwidth-v0.4.0.apk',
    [string]$OutputApk = 'D:\NSFC\output\apk\rail-bandwidth-v0.7.3-tunnel.apk',
    [string]$JavaHome = 'D:\Android\Android Studio\jbr'
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe')) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\bin;$env:PATH"
}
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$source = Join-Path $project 'android\app\src\main'
$build = Join-Path $project 'android\build\local-v07'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$assets = Join-Path $build 'assets'
$native = Join-Path $build 'native'
foreach ($path in @($build,$classes,$dex,$assets,$native,(Split-Path $OutputApk))) {
    New-Item -ItemType Directory -Path $path -Force | Out-Null
}
$platform = Join-Path $SdkRoot 'platforms\android-37.0\android.jar'
$tools = Join-Path $SdkRoot 'build-tools\36.0.0'
$manifest = (Get-Content (Join-Path $source 'AndroidManifest.xml') -Raw).
    Replace('package="cn.railband.mvp"','package="cn.railband.mvp.radio"').
    Replace('android:name=".MainActivity"','android:name="cn.railband.mvp.MainActivity"').
    Replace('android:name=".MeasureService"','android:name="cn.railband.mvp.MeasureService"').
    Replace('android:name=".UploadJob"','android:name="cn.railband.mvp.UploadJob"').
    Replace('android:label="轨途测速"','android:label="轨途测速 5G"')
Set-Content -LiteralPath (Join-Path $build 'AndroidManifest.xml') -Value $manifest -Encoding UTF8

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$previous = [System.IO.Compression.ZipFile]::OpenRead($PreviousApk)
try {
    foreach ($entry in $previous.Entries) {
        if ($entry.FullName -eq 'assets/IPERF-LICENSE.txt' -or $entry.FullName -like 'lib/*/libiperf_exec.so') {
            $target = Join-Path $build ($entry.FullName.Replace('/','\'))
            New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$true)
        }
    }
} finally { $previous.Dispose() }
& (Join-Path $tools 'aapt2.exe') compile --dir (Join-Path $source 'res') -o (Join-Path $build 'resources.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
& (Join-Path $tools 'aapt2.exe') link -o (Join-Path $build 'base.apk') --manifest (Join-Path $build 'AndroidManifest.xml') -I $platform -A $assets (Join-Path $build 'resources.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

$java = (Get-ChildItem (Join-Path $source 'java') -Recurse -Filter '*.java').FullName
& javac -encoding UTF-8 -source 8 -target 8 -classpath $platform -d $classes $java
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
& jar cf (Join-Path $build 'classes.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
& (Join-Path $tools 'd8.bat') --lib $platform --min-api 28 --output $dex (Join-Path $build 'classes.jar')
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Copy-Item (Join-Path $build 'base.apk') (Join-Path $build 'unsigned.apk') -Force
$package = [System.IO.Compression.ZipFile]::Open((Join-Path $build 'unsigned.apk'),[System.IO.Compression.ZipArchiveMode]::Update)
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($package,(Join-Path $dex 'classes.dex'),'classes.dex',[System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    foreach ($abi in @('arm64-v8a','x86_64')) {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($package,(Join-Path $build "lib\$abi\libiperf_exec.so"),"lib/$abi/libiperf_exec.so",[System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
    }
} finally { $package.Dispose() }

& (Join-Path $tools 'zipalign.exe') -f -P 16 4 (Join-Path $build 'unsigned.apk') (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
$key = 'D:\NSFC\tools\android\rail-radio-debug.jks'
if (-not (Test-Path -LiteralPath $key)) {
    & keytool -genkeypair -keystore $key -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=RailBand Radio Development,O=RailBand,C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}
& (Join-Path $tools 'apksigner.bat') sign --ks $key --ks-pass pass:android --key-pass pass:android --out $OutputApk (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
& (Join-Path $tools 'apksigner.bat') verify --verbose $OutputApk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
& (Join-Path $tools 'zipalign.exe') -c -P 16 4 $OutputApk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment verification failed' }
Get-FileHash -Algorithm SHA256 $OutputApk | Select-Object Path,Hash
