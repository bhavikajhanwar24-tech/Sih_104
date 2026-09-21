# Print the primary LAN IPv4 (for SIP_EXTERNAL_IP). Prefers Wi-Fi / Ethernet.
$ErrorActionPreference = 'Stop'
$candidates = Get-NetIPAddress -AddressFamily IPv4 |
  Where-Object {
    $_.IPAddress -notlike '127.*' -and
    $_.IPAddress -notlike '169.254.*' -and
    $_.PrefixOrigin -ne 'WellKnown'
  } |
  Sort-Object -Property InterfaceMetric

$preferred = $candidates |
  Where-Object {
    $_.InterfaceAlias -match 'Wi-?Fi|Ethernet|WLAN|LAN'
  } |
  Select-Object -First 1

if ($preferred) {
  Write-Output $preferred.IPAddress
} elseif ($candidates) {
  Write-Output $candidates[0].IPAddress
} else {
  Write-Error 'No LAN IPv4 found'
  exit 1
}
