$ErrorActionPreference='Stop'
$sdk='D:\Android\SDK'
$bt=Join-Path $sdk 'build-tools\36.0.0'
$platform=Join-Path $sdk 'platforms\android-37.0\android.jar'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out=Join-Path $root 'android\build\journey-test'
New-Item -ItemType Directory -Force -Path "$out\classes","$out\dex" | Out-Null
$manifest='<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="cn.railband.mvp.journeytest"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36"/><application android:label="Journey integration test"/><instrumentation android:name="cn.railband.mvp.JourneyTest" android:targetPackage="cn.railband.mvp.radio" android:functionalTest="true"/><instrumentation android:name="cn.railband.mvp.JourneyUploadTest" android:targetPackage="cn.railband.mvp.radio" android:functionalTest="true"/><instrumentation android:name="cn.railband.mvp.ReconnectTest" android:targetPackage="cn.railband.mvp.radio" android:functionalTest="true"/><instrumentation android:name="cn.railband.mvp.StallRecoveryTest" android:targetPackage="cn.railband.mvp.radio" android:functionalTest="true"/></manifest>'
Set-Content -LiteralPath "$out\AndroidManifest.xml" -Value $manifest -Encoding UTF8
& javac -encoding UTF-8 -source 8 -target 8 -classpath "$platform;$root\android\build\local-v07\classes.jar" -d "$out\classes" "$PSScriptRoot\JourneyTest.java" "$PSScriptRoot\JourneyUploadTest.java" "$PSScriptRoot\ReconnectTest.java" "$PSScriptRoot\StallRecoveryTest.java"
if($LASTEXITCODE -ne 0){throw 'Test compilation failed'}
& jar cf "$out\classes.jar" -C "$out\classes" .
& "$bt\d8.bat" --lib $platform --classpath "$root\android\build\local-v07\classes.jar" --min-api 28 --output "$out\dex" "$out\classes.jar"
& "$bt\aapt2.exe" link -o "$out\unsigned.apk" --manifest "$out\AndroidManifest.xml" -I $platform
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::Open("$out\unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try{[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$out\dex\classes.dex",'classes.dex') | Out-Null}finally{$zip.Dispose()}
& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
& "$bt\apksigner.bat" sign --ks D:\NSFC\tools\android\rail-radio-debug.jks --ks-pass pass:android --out "$out\journey-test.apk" "$out\aligned.apk"
if($LASTEXITCODE -ne 0){throw 'Test signing failed'}
