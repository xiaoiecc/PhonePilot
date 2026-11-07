$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$archivePath = Join-Path $repoRoot 'work/mihomo-android-arm64.gz'
$nativePath = Join-Path $repoRoot 'app/src/main/jniLibs/arm64-v8a/libmihomo.so'
$archiveHash = '2DDBD3AAA884F52B4568F406876EC81DDDEEF9F1BB67B90D4004D356E2C6A708'
$nativeHash = 'CFA0BE78846BFEF7272FE5A73089F8CF0E1F15A6FFB601F5CC809F457F712F67'
if (Test-Path -LiteralPath $nativePath) {
    if ((Get-FileHash -LiteralPath $nativePath).Hash -ne $nativeHash) { throw 'Existing core has an unexpected hash. It was not changed.' }
    Write-Output 'Verified existing Mihomo v1.19.33 Android arm64 core.'
    exit 0
}
New-Item -ItemType Directory -Path (Split-Path -Parent $archivePath), (Split-Path -Parent $nativePath) -Force | Out-Null
if (-not (Test-Path -LiteralPath $archivePath)) {
    Invoke-WebRequest -Uri 'https://github.com/MetaCubeX/mihomo/releases/download/v1.19.33/mihomo-android-arm64-v8-v1.19.33.gz' -OutFile $archivePath
}
if ((Get-FileHash -LiteralPath $archivePath).Hash -ne $archiveHash) { throw 'Downloaded core checksum mismatch.' }
$source = [IO.File]::OpenRead($archivePath)
$gzip = [IO.Compression.GzipStream]::new($source, [IO.Compression.CompressionMode]::Decompress)
$target = [IO.File]::Open($nativePath, [IO.FileMode]::CreateNew)
try { $gzip.CopyTo($target) } finally { $target.Dispose(); $gzip.Dispose(); $source.Dispose() }
if ((Get-FileHash -LiteralPath $nativePath).Hash -ne $nativeHash) { throw 'Extracted core checksum mismatch.' }
Write-Output 'Mihomo v1.19.33 Android arm64 core ready.'
