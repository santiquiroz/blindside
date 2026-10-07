# Run once in an elevated PowerShell. Exposes only the OpenTAKServer TLS port (8089) of the WSL2 distro on the PC's LAN address.
param(
    [Parameter(Mandatory = $true)][string]$LanIp,
    [int]$Port = 8089
)

$ErrorActionPreference = 'Stop'

# WSL2 (NAT mode) relays 127.0.0.1:<port> into the distro; portproxy carries the LAN address to that relay.
netsh interface portproxy delete v4tov4 listenaddress=$LanIp listenport=$Port 2>$null | Out-Null
netsh interface portproxy add v4tov4 listenaddress=$LanIp listenport=$Port connectaddress=127.0.0.1 connectport=$Port
Set-Service iphlpsvc -StartupType Automatic
Start-Service iphlpsvc

$ruleName = "OpenTAKServer TLS $Port"
Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort $Port -LocalAddress $LanIp -Action Allow -Profile Any | Out-Null

netsh interface portproxy show v4tov4
Get-NetFirewallRule -DisplayName $ruleName | Select-Object DisplayName, Enabled, Direction, Action
