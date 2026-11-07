# Builds Arr411.apk (C411 + qBittorrent + Radarr + Sonarr + Lidarr) without Gradle. Usage: powershell -File build.ps1
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$parent = Split-Path $root
# Toolchain (JDK 17 + Android SDK): $env:ARR411_TOOLCHAIN, or the android-toolchain folder next to this one.
$tc = if ($env:ARR411_TOOLCHAIN) { $env:ARR411_TOOLCHAIN } else { Join-Path $parent 'android-toolchain' }
if (-not (Test-Path "$tc\jdk\bin\javac.exe")) { throw "JDK 17 not found in $tc\jdk (see README, Building from source)" }
# Extension sources (arr411-chrome): $env:ARR411_EXTENSION, or the arr411-chrome repository next to this one.
$ext = if ($env:ARR411_EXTENSION) { $env:ARR411_EXTENSION } else { Join-Path $parent 'arr411-chrome' }
foreach ($f in 'background.js', 'content.js', 'page-hook.js') {
  if (-not (Test-Path "$ext\$f")) { throw "Extension sources not found in $ext (clone https://github.com/HugoMeyer/arr411-chrome next to this repository, or set ARR411_EXTENSION)" }
}
$bt = "$tc\sdk\build-tools\34.0.0"
$jar = "$tc\sdk\platforms\android-34\android.jar"
$env:JAVA_HOME = "$tc\jdk"; $env:PATH = "$tc\jdk\bin;$env:PATH"
$b = "$root\build"
Remove-Item $b -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$b\assets", "$b\gen", "$b\classes", "$b\dex" | Out-Null
$enc = New-Object Text.UTF8Encoding($false)

# Assets: the extension's engine/content scripts + Android shims + the Arr411 SPA (served from https://arr411.local/app/).
foreach ($f in 'background.js', 'content.js', 'page-hook.js') {
  Copy-Item "$ext\$f" "$b\assets\$f"
}
Copy-Item "$root\assets-extra\*" "$b\assets\"
Copy-Item "$root\app" "$b\assets\app" -Recurse

& "$bt\aapt2.exe" compile --dir "$root\res" -o "$b\res.zip"; if ($LASTEXITCODE) { throw 'aapt2 compile' }
& "$bt\aapt2.exe" link -o "$b\base.apk" -I $jar --manifest "$root\AndroidManifest.xml" --java "$b\gen" "$b\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 link' }

$src = @(Get-ChildItem "$root\src", "$b\gen" -Recurse -Filter *.java | ForEach-Object FullName)
& javac -encoding UTF-8 --release 11 -nowarn -classpath $jar -d "$b\classes" $src; if ($LASTEXITCODE) { throw 'javac' }
$classes = @(Get-ChildItem "$b\classes" -Recurse -Filter *.class | ForEach-Object FullName)
& "$bt\d8.bat" --release --lib $jar --min-api 29 --output "$b\dex" $classes; if ($LASTEXITCODE) { throw 'd8' }

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
Copy-Item "$b\base.apk" "$b\unaligned.apk"
$z = [IO.Compression.ZipFile]::Open("$b\unaligned.apk", 'Update')
[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($z, "$b\dex\classes.dex", 'classes.dex')
# Assets are added here rather than with aapt2 -A, which writes '\' separators for nested paths on Windows.
$abase = (Resolve-Path "$b\assets").Path.TrimEnd('\') + '\'
foreach ($f in Get-ChildItem "$b\assets" -Recurse -File) {
  $name = 'assets/' + $f.FullName.Substring($abase.Length).Replace('\', '/')
  [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($z, $f.FullName, $name)
}
$z.Dispose()
& "$bt\zipalign.exe" -f -p 4 "$b\unaligned.apk" "$b\aligned.apk"; if ($LASTEXITCODE) { throw 'zipalign' }

$ks = "$root\signing.keystore"
if (-not (Test-Path $ks)) {
  & keytool -genkeypair -keystore $ks -alias c411 -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass c411qbit -keypass c411qbit -dname "CN=C411 qBittorrent" 2>$null
}
$out = "$root\Arr411.apk"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:c411qbit --ks-key-alias c411 --out $out "$b\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner' }
& "$bt\apksigner.bat" verify $out; if ($LASTEXITCODE) { throw 'verify' }
$dl = Join-Path $env:USERPROFILE 'Downloads'
if (Test-Path $dl) { Copy-Item $out "$dl\Arr411.apk" -Force }
"APK: $out ($((Get-Item $out).Length) bytes)"
