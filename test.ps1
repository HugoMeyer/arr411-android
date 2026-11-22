# Runs the checks of the classes of src that don't use android.* (test\PureTests.java) with the toolchain's JDK,
# then the checks of assets-extra\shim-page.js (test\shim, with node when it is installed).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File test.ps1
# Exit code 0 when every check passes, 1 otherwise.
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
# Toolchain (JDK 17): $env:ARR411_TOOLCHAIN, or the android-toolchain folder next to this one; failing that, any JDK 11+ from JAVA_HOME or the PATH.
$tc = if ($env:ARR411_TOOLCHAIN) { $env:ARR411_TOOLCHAIN } else { Join-Path (Split-Path $root) 'android-toolchain' }
$javac = "$tc\jdk\bin\javac.exe"
$java = "$tc\jdk\bin\java.exe"
if (-not (Test-Path $javac)) {
  $javac = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { "$env:JAVA_HOME\bin\javac.exe" } else { (Get-Command javac -ErrorAction SilentlyContinue).Source }
  $java = if ($javac) { Join-Path (Split-Path $javac) 'java.exe' }
  if (-not $javac -or -not (Test-Path $java)) { throw "JDK not found in $tc\jdk, JAVA_HOME or the PATH (see README, Building from source)" }
}

# The classes under test must stay free of android.* so a plain JDK can run them.
$pure = 'BodyReader', 'BridgeKey', 'ConsoleBudget', 'CookieJars', 'DiagText', 'FileNames', 'HashedCopy', 'LogFile', 'LruThrottle', 'PageGate', 'Redact', 'RedirectPolicy', 'RestartDelay', 'SaveText', 'UpdateFailure', 'UpdateFiles', 'UpdateFlow', 'UpdateHosts', 'UpdateInstallStatus', 'UpdateIntegrity', 'UpdateJson', 'UpdateRelease', 'UpdateState', 'UpdateThrottle', 'UpdateVersion', 'UrlPolicy'
$sources = @()
foreach ($name in $pure) {
  $file = "$root\src\dev\c411\qbittorrent\$name.java"
  if (-not (Test-Path $file)) { throw "$file not found" }
  if (Select-String -Path $file -Pattern '^\s*import\s+(android|androidx)\.' -Quiet) { throw "$name.java imports android.*: it cannot be tested here" }
  $sources += $file
}
$sources += "$root\test\PureTests.java"

$out = "$root\test\out"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes", "$out\tmp" | Out-Null
& $javac -encoding UTF-8 --release 11 -nowarn -d "$out\classes" $sources
if ($LASTEXITCODE) { throw 'javac' }
& $java "-Djava.io.tmpdir=$out\tmp" "-Darr411.src=$root\src\dev\c411\qbittorrent" "-Darr411.fixtures=$root\test\fixtures" -cp "$out\classes" dev.c411.qbittorrent.PureTests
$code = $LASTEXITCODE

$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if ($node) {
  & $node --test "$root\test\shim\shim-page.test.js"
  if ($LASTEXITCODE) { $code = 1 }
} else {
  Write-Host 'node not found: the checks of assets-extra\shim-page.js (test\shim) were skipped'
}
exit $code
