<#
.SYNOPSIS
    Acceptance test 8: proves a bridged drive survives a sustained write.

.DESCRIPTION
    Reads are already proven on real hardware. Writes are not, and the bulk
    transfer chunking in the USB/IP server is exactly the code a large write
    exercises hardest. A silent corruption there would be worse than an outright
    failure, because it looks like success.

    So this writes files of random data to the drive, recording a SHA-256 of
    each as it goes, then reads every one back and compares. Any mismatch means
    data was altered in flight.

    It only ever touches a folder it creates itself, and deletes that folder at
    the end unless -Keep is given. It never formats, partitions or writes
    anywhere else on the disk.

.PARAMETER Drive
    Drive letter of the bridged volume, e.g. E. Required, so the wrong disk
    cannot be picked by accident.

.PARAMETER TotalGB
    How much to write in total. Default 4.

.PARAMETER FileMB
    Size of each test file. Default 256.

.PARAMETER Keep
    Leave the test folder in place afterwards.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Test-DriveIntegrity.ps1 -Drive E

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Test-DriveIntegrity.ps1 -Drive E -TotalGB 16
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z]$')]
    [string] $Drive,

    [ValidateRange(1, 512)]  [int] $TotalGB = 4,
    [ValidateRange(16, 4096)][int] $FileMB = 256,
    [switch] $Keep
)

$ErrorActionPreference = 'Stop'

function Write-Step ([string] $T) { Write-Host "`n=== $T" -ForegroundColor Cyan }
function Write-Good ([string] $T) { Write-Host "    $T" -ForegroundColor Green }
function Write-Bad  ([string] $T) { Write-Host "    $T" -ForegroundColor Red }
function Write-Info ([string] $T) { Write-Host "    $T" }

$root = "${Drive}:\"
if (-not (Test-Path -LiteralPath $root)) {
    Write-Bad "Drive $Drive`: is not available."
    return
}

$volume = Get-Volume -DriveLetter $Drive -ErrorAction SilentlyContinue
if ($volume) {
    Write-Info ("Volume: {0} {1}, {2:N1} GB free of {3:N1} GB" -f `
        $volume.FileSystemLabel, $volume.FileSystem,
        ($volume.SizeRemaining / 1GB), ($volume.Size / 1GB))
}

$fileCount = [math]::Max(1, [int](($TotalGB * 1024) / $FileMB))
$totalBytes = [long]$fileCount * $FileMB * 1MB
if ($volume -and $volume.SizeRemaining -lt ($totalBytes * 1.1)) {
    Write-Bad ("Not enough free space: need about {0:N1} GB." -f ($totalBytes * 1.1 / 1GB))
    return
}

$folder = Join-Path $root ("PerspectiveIntegrity_" + (Get-Random -Maximum 999999))
New-Item -ItemType Directory -Path $folder | Out-Null
Write-Step "Writing $fileCount x $FileMB MB to $folder"

$expected = @{}
$buffer = New-Object byte[] (1MB)
$random = New-Object System.Random 20260824   # fixed seed: reproducible runs
$writeWatch = [Diagnostics.Stopwatch]::StartNew()

try {
    for ($i = 1; $i -le $fileCount; $i++) {
        $file = Join-Path $folder "block-$i.bin"
        $sha = [Security.Cryptography.SHA256]::Create()
        $stream = [IO.File]::Open($file, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        try {
            for ($mb = 0; $mb -lt $FileMB; $mb++) {
                $random.NextBytes($buffer)
                $stream.Write($buffer, 0, $buffer.Length)
                [void]$sha.TransformBlock($buffer, 0, $buffer.Length, $null, 0)
            }
            # Force it to the device rather than sitting in Windows' cache, so
            # the bytes really cross the bridge before we read them back.
            $stream.Flush($true)
        } finally { $stream.Dispose() }
        [void]$sha.TransformFinalBlock(@(), 0, 0)
        $expected[$file] = [BitConverter]::ToString($sha.Hash).Replace('-', '')
        $sha.Dispose()
        Write-Progress -Activity 'Writing' -Status "$i of $fileCount" -PercentComplete ($i * 100 / $fileCount)
    }
    $writeWatch.Stop()
    $writeSeconds = [math]::Max(0.001, $writeWatch.Elapsed.TotalSeconds)
    Write-Good ("Wrote {0:N1} GB in {1:N0} s -> {2:N1} MB/s" -f `
        ($totalBytes / 1GB), $writeSeconds, ($totalBytes / 1MB / $writeSeconds))

    # Drop the cache so the verify pass genuinely re-reads the device.
    Write-Step 'Remounting to clear the Windows cache'
    try {
        Get-Volume -DriveLetter $Drive | Get-Partition | Get-Disk | Set-Disk -IsOffline $true
        Start-Sleep -Seconds 2
        Get-Volume -DriveLetter $Drive | Get-Partition | Get-Disk | Set-Disk -IsOffline $false
        Start-Sleep -Seconds 3
        Write-Good 'Remounted.'
    } catch {
        Write-Info "Could not remount ($($_.Exception.Message)); the verify pass may read from cache."
    }

    Write-Step 'Reading back and verifying'
    $readWatch = [Diagnostics.Stopwatch]::StartNew()
    $bad = @()
    $checked = 0
    foreach ($file in $expected.Keys) {
        if (-not (Test-Path -LiteralPath $file)) {
            $bad += "MISSING  $file"
            continue
        }
        $actual = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
        if ($actual -ne $expected[$file]) {
            $bad += "MISMATCH $file"
        }
        $checked++
        Write-Progress -Activity 'Verifying' -Status "$checked of $fileCount" -PercentComplete ($checked * 100 / $fileCount)
    }
    $readWatch.Stop()
    $readSeconds = [math]::Max(0.001, $readWatch.Elapsed.TotalSeconds)
    Write-Good ("Read {0:N1} GB in {1:N0} s -> {2:N1} MB/s" -f `
        ($totalBytes / 1GB), $readSeconds, ($totalBytes / 1MB / $readSeconds))

    Write-Step 'Result'
    if ($bad.Count -eq 0) {
        Write-Good "All $fileCount files verified byte-for-byte. Acceptance test 8 passes."
    } else {
        Write-Bad "$($bad.Count) of $fileCount files did not match:"
        $bad | ForEach-Object { Write-Info $_ }
        Write-Bad 'Data was altered in transit. Do not trust the bridge with writes.'
        Write-Info 'Capture the Android diagnostics panel and report this.'
    }
} finally {
    if ($Keep) {
        Write-Info "Left test files in $folder"
    } else {
        Write-Info 'Removing test files...'
        Remove-Item -LiteralPath $folder -Recurse -Force -ErrorAction SilentlyContinue
    }
}
