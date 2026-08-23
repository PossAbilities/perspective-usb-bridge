<#
.SYNOPSIS
    Connects a drive shared by Perspective USB Bridge on an Android tablet to
    this Windows PC, end to end.

.DESCRIPTION
    Does everything the Windows side needs, in order, and says what it found at
    each step:

      1. Re-launches itself elevated if needed.
      2. Reports any duplicate USB/IP driver installs, which make the runtime
         refuse to start with "Multiple instances of VHCI device interface
         found".
      3. Locates usbip.exe, and installs the runtime bundled with Perspective
         if it is missing.
      4. Finds the tablet, by UDP discovery or from -TabletIp.
      5. Lists the shared drives and attaches one.
      6. Waits for the disk, brings it online and gives it a drive letter if it
         has a partition without one.

    It never initialises, formats or partitions anything, so it cannot destroy
    data on the drive. Bringing a disk online and assigning a letter are the
    only changes it makes, and -NoMount turns even those off.

.PARAMETER TabletIp
    The tablet's address. Omit to discover it automatically.

.PARAMETER BusId
    Which shared device to attach. Omit to take the only one, or the first
    mass-storage device when several are shared.

.PARAMETER NoMount
    Report the disk but do not bring it online or assign a drive letter.

.PARAMETER SkipInstall
    Never install the driver, only report on it.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Connect-PerspectiveDrive.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Connect-PerspectiveDrive.ps1 -TabletIp 192.168.1.92
#>
[CmdletBinding()]
param(
    [string] $TabletIp,
    [string] $BusId,
    [switch] $NoMount,
    [switch] $SkipInstall
)

$ErrorActionPreference = 'Stop'
$DiscoveryPort = 32401
$DiscoveryProbe = 'PERSPECTIVE_USB_BRIDGE_DISCOVER'
$DiscoveryMagic = 'PERSPECTIVE_USB_BRIDGE_V2'

function Write-Step  ([string] $Text) { Write-Host "`n=== $Text" -ForegroundColor Cyan }
function Write-Good  ([string] $Text) { Write-Host "    $Text" -ForegroundColor Green }
function Write-Warn  ([string] $Text) { Write-Host "    $Text" -ForegroundColor Yellow }
function Write-Bad   ([string] $Text) { Write-Host "    $Text" -ForegroundColor Red }
function Write-Info  ([string] $Text) { Write-Host "    $Text" }

# ---------------------------------------------------------------- elevation

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Administrator)) {
    Write-Warn 'Not running as Administrator. Re-launching with elevation...'
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"")
    if ($TabletIp)   { $arguments += @('-TabletIp', $TabletIp) }
    if ($BusId)      { $arguments += @('-BusId', $BusId) }
    if ($NoMount)    { $arguments += '-NoMount' }
    if ($SkipInstall){ $arguments += '-SkipInstall' }
    Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -Verb RunAs
    return
}

Write-Host 'Perspective USB Bridge - Windows connect helper' -ForegroundColor Magenta

# ------------------------------------------------------- duplicate driver check

Write-Step 'Checking for duplicate USB/IP installs'

$vhciDevices = @()
try {
    $vhciDevices = @(Get-PnpDevice -ErrorAction Stop | Where-Object {
        $_.InstanceId -match 'VHCI|USBIP' -or $_.FriendlyName -match 'usb.?ip|vhci'
    })
} catch {
    Write-Warn "Could not enumerate devices: $($_.Exception.Message)"
}

if ($vhciDevices.Count -gt 1) {
    Write-Bad "$($vhciDevices.Count) USB/IP virtual controllers are registered. The runtime will refuse to run."
    foreach ($device in $vhciDevices) {
        Write-Info "  $($device.FriendlyName) [$($device.InstanceId)] $($device.Status)"
    }
    Write-Bad 'Remove all but one, reboot, then run this script again:'
    foreach ($device in $vhciDevices) {
        Write-Info "  pnputil /remove-device `"$($device.InstanceId)`""
    }
    return
} elseif ($vhciDevices.Count -eq 1) {
    Write-Good "One virtual controller: $($vhciDevices[0].FriendlyName) ($($vhciDevices[0].Status))"
} else {
    Write-Info 'No virtual controller yet; the driver has not been installed.'
}

# ------------------------------------------------------------------- runtime

function Find-Usbip {
    $candidates = @()
    $onPath = Get-Command 'usbip.exe' -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    $candidates += @(
        'C:\Program Files\USBip\usbip.exe',
        'C:\Program Files\usbip-win2\usbip.exe',
        'C:\Program Files (x86)\USBip\usbip.exe',
        'C:\Program Files (x86)\usbip-win2\usbip.exe'
    )
    $uninstallKeys = @(
        'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
    )
    foreach ($key in $uninstallKeys) {
        Get-ItemProperty $key -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -match 'usb.?ip' -and $_.InstallLocation } |
            ForEach-Object { $candidates += (Join-Path $_.InstallLocation 'usbip.exe') }
    }
    return $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
}

function Find-BundledInstaller {
    $roots = @(
        "$env:ProgramFiles\Perspective USB Bridge\resources\runtime",
        "${env:ProgramFiles(x86)}\Perspective USB Bridge\resources\runtime",
        "$env:LOCALAPPDATA\Programs\Perspective USB Bridge\resources\runtime"
    )
    foreach ($root in $roots) {
        $candidate = Join-Path $root 'usbip-win2-installer.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}

Write-Step 'Locating the USB/IP runtime'
$usbip = Find-Usbip

if (-not $usbip -and -not $SkipInstall) {
    $installer = Find-BundledInstaller
    if (-not $installer) {
        Write-Bad 'usbip.exe not found, and no bundled installer alongside Perspective USB Bridge.'
        Write-Info 'Install Perspective USB Bridge first, or pass -SkipInstall to skip this step.'
        return
    }
    Write-Info "Installing the bundled runtime: $installer"
    $process = Start-Process -FilePath $installer `
        -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait -PassThru
    if ($process.ExitCode -ne 0 -and $process.ExitCode -ne 3010) {
        Write-Bad "The driver installer exited with code $($process.ExitCode)."
        return
    }
    if ($process.ExitCode -eq 3010) { Write-Warn 'The installer asked for a restart.' }
    Start-Sleep -Seconds 3
    $usbip = Find-Usbip
}

if (-not $usbip) {
    Write-Bad 'usbip.exe is still not present. Reboot and run this script again.'
    return
}
Write-Good "Runtime: $usbip"

# usbip reports driver-level faults on any subcommand, so `port` is a cheap probe.
$portOutput = & $usbip port 2>&1 | Out-String
if ($portOutput -match 'Multiple instances') {
    Write-Bad 'The runtime reports multiple VHCI instances. Remove the duplicate driver and reboot.'
    Write-Info $portOutput.Trim()
    return
}
Write-Good 'Runtime responds normally.'

# ----------------------------------------------------------------- discovery

function Find-Tablet {
    $client = New-Object System.Net.Sockets.UdpClient
    try {
        $client.EnableBroadcast = $true
        $client.Client.ReceiveTimeout = 1500
        $payload = [Text.Encoding]::UTF8.GetBytes($DiscoveryProbe)
        $broadcast = New-Object System.Net.IPEndPoint([Net.IPAddress]::Broadcast, $DiscoveryPort)

        foreach ($attempt in 1..6) {
            [void] $client.Send($payload, $payload.Length, $broadcast)
            $remote = New-Object System.Net.IPEndPoint([Net.IPAddress]::Any, 0)
            try {
                $data = $client.Receive([ref] $remote)
                $text = [Text.Encoding]::UTF8.GetString($data)
                if ($text.StartsWith($DiscoveryMagic)) { return $remote.Address.ToString() }
            } catch [System.Net.Sockets.SocketException] {
                # receive timed out; probe again
            }
        }
    } finally {
        $client.Close()
    }
    return $null
}

if (-not $TabletIp) {
    Write-Step 'Looking for the tablet'
    $TabletIp = Find-Tablet
    if (-not $TabletIp) {
        Write-Bad 'No tablet answered on the network.'
        Write-Info 'Open Perspective USB Bridge on the tablet and leave it on screen, check both'
        Write-Info 'devices are on the same Wi-Fi, then re-run, or pass -TabletIp <address>.'
        return
    }
}
Write-Good "Tablet: $TabletIp"

# ------------------------------------------------------------------- attach

Write-Step 'Listing shared drives'
$listOutput = & $usbip list -r $TabletIp 2>&1 | Out-String
Write-Info $listOutput.Trim()

$devices = @()
foreach ($line in ($listOutput -split "`r?`n")) {
    if ($line -match '^\s*-?\s*(?<bus>[0-9]+-[0-9]+(?:[.\-][0-9]+)*)\s*:\s*(?<name>.+?)\s*$') {
        $devices += [pscustomobject]@{
            BusId = $Matches['bus']
            Name  = $Matches['name'].Trim()
        }
    }
}

if ($devices.Count -eq 0) {
    Write-Bad 'The tablet is reachable but nothing is shared.'
    Write-Info 'Tap Allow access, then Share with Windows, on the tablet.'
    return
}
foreach ($device in $devices) { Write-Good "$($device.BusId)  $($device.Name)" }

if (-not $BusId) { $BusId = $devices[0].BusId }
if ($devices.BusId -notcontains $BusId) {
    Write-Bad "Bus ID $BusId is not shared. Available: $($devices.BusId -join ', ')"
    return
}

Write-Step "Attaching $BusId"
$before = @(Get-Disk | Select-Object -ExpandProperty Number)
$attachOutput = & $usbip attach -r $TabletIp -b $BusId 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Bad "Attach failed: $($attachOutput.Trim())"
    return
}
if ($attachOutput.Trim()) { Write-Info $attachOutput.Trim() }
Write-Good 'Attached. Waiting for Windows to enumerate the disk...'

# --------------------------------------------------------------------- disk

$newDisk = $null
foreach ($attempt in 1..30) {
    Start-Sleep -Seconds 1
    $after = @(Get-Disk)
    $candidate = $after | Where-Object { $before -notcontains $_.Number }
    if ($candidate) { $newDisk = @($candidate)[0]; break }
}

if (-not $newDisk) {
    Write-Bad 'The device attached but Windows never created a disk for it.'
    Write-Info 'Check Device Manager for a USB Mass Storage Device with a warning icon,'
    Write-Info 'and the diagnostics panel in the Android app for USB/IP errors.'
    Write-Info "Detach with:  `"$usbip`" detach --all"
    return
}

Write-Good ("Disk {0}: {1}, {2:N1} GB, {3}, partition style {4}" -f `
    $newDisk.Number, $newDisk.FriendlyName, ($newDisk.Size / 1GB), $newDisk.OperationalStatus, $newDisk.PartitionStyle)

if ($newDisk.PartitionStyle -eq 'RAW') {
    Write-Bad 'Windows sees the disk but cannot read a partition table on it.'
    Write-Info 'The tablet can read this drive, so the data is intact. This points at corrupted'
    Write-Info 'reads over USB/IP rather than a problem with the drive itself. Do NOT initialise'
    Write-Info 'the disk when Windows offers to - that would destroy the partition table.'
    return
}

if ($NoMount) {
    Write-Info 'Leaving the disk as-is (-NoMount).'
    return
}

if ($newDisk.IsOffline) {
    Write-Info 'Disk is offline; bringing it online.'
    Set-Disk -Number $newDisk.Number -IsOffline $false
    if ($newDisk.IsReadOnly) { Set-Disk -Number $newDisk.Number -IsReadOnly $false }
    Start-Sleep -Seconds 2
}

$partitions = @(Get-Partition -DiskNumber $newDisk.Number -ErrorAction SilentlyContinue |
    Where-Object { $_.Size -gt 64MB })

if ($partitions.Count -eq 0) {
    Write-Warn 'The disk is online but has no usable partition.'
    return
}

foreach ($partition in $partitions) {
    if ($partition.DriveLetter) {
        Write-Good "Drive $($partition.DriveLetter): is ready."
        continue
    }
    try {
        $free = [char[]](70..90) | Where-Object {
            -not (Get-PSDrive -Name $_ -ErrorAction SilentlyContinue)
        } | Select-Object -First 1
        if (-not $free) { Write-Warn 'No spare drive letters.'; continue }
        Set-Partition -DiskNumber $newDisk.Number -PartitionNumber $partition.PartitionNumber -NewDriveLetter $free
        Write-Good "Assigned drive $free`: to partition $($partition.PartitionNumber)."
    } catch {
        Write-Warn "Could not assign a letter to partition $($partition.PartitionNumber): $($_.Exception.Message)"
    }
}

Write-Step 'Done'
Write-Info "Disconnect later with:  `"$usbip`" detach --all"
