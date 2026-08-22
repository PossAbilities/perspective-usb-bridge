$ErrorActionPreference = 'Stop'

$repo = 'vadimgrn/usbip-win2'
$api = "https://api.github.com/repos/$repo/releases/latest"
$headers = @{ 'User-Agent' = 'Perspective-USB-Bridge-GitHub-Actions' }
if ($env:GITHUB_TOKEN) { $headers['Authorization'] = "Bearer $env:GITHUB_TOKEN" }

Write-Host "Resolving latest WHLK-certified USB/IP runtime from $repo..."
$release = Invoke-RestMethod -Uri $api -Headers $headers

$asset = $release.assets | Where-Object {
    $_.name -match '(?i)^USBip-.*-x64(?:-release)?\.exe$'
} | Select-Object -First 1

if (-not $asset) {
    Write-Host 'Available release assets:'
    $release.assets | ForEach-Object { Write-Host " - $($_.name)" }
    throw 'Could not identify the upstream x64 USB/IP installer. Refusing to package an unknown driver.'
}

$runtimeDir = Join-Path $PSScriptRoot '..\..\windows\runtime'
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
$destination = Join-Path $runtimeDir 'usbip-win2-installer.exe'
Invoke-WebRequest -Uri $asset.browser_download_url -Headers $headers -OutFile $destination

$hash = (Get-FileHash -Algorithm SHA256 -Path $destination).Hash
@"
Upstream: https://github.com/$repo
Release: $($release.tag_name)
Asset: $($asset.name)
SHA256: $hash
Fetched: $(Get-Date -Format o)
"@ | Set-Content -Encoding UTF8 (Join-Path $runtimeDir 'RUNTIME-BUILD-INFO.txt')

$licenseUrl = 'https://raw.githubusercontent.com/vadimgrn/usbip-win2/master/LICENSE.txt'
Invoke-WebRequest -Uri $licenseUrl -Headers $headers -OutFile (Join-Path $runtimeDir 'USBIP-WIN2-LICENSE.txt')

Write-Host "Bundled $($asset.name) ($($release.tag_name))"
Write-Host "SHA256 $hash"
