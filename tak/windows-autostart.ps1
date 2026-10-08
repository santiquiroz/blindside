# Run once in an elevated PowerShell. Starts the OpenTAKServer WSL distro at boot, before anyone logs in,
# and optionally maps the server's public name to this PC so the admin UI generates packages with that name.
param(
    [string]$Distro = "Ubuntu-24.04",
    [string]$LocalDomain = "",
    [switch]$Test
)

$ErrorActionPreference = 'Stop'
$taskName = "OpenTAKServer WSL"

if ($LocalDomain) {
    $hosts = "$env:SystemRoot\System32\drivers\etc\hosts"
    $pattern = [regex]::Escape($LocalDomain)
    if (-not (Select-String -Path $hosts -Pattern "\s$pattern(\s|$)" -Quiet)) {
        # The admin UI writes the host it was opened with into every package, so it must be opened with the public name.
        Add-Content -Path $hosts -Value "`r`n127.0.0.1 $LocalDomain # OpenTAKServer: interfaz local con el nombre publico" -Encoding ascii
    }
    Write-Output "hosts: $LocalDomain -> 127.0.0.1"
}

$action = New-ScheduledTaskAction -Execute "wsl.exe" -Argument "-d $Distro --exec sleep infinity"
$trigger = New-ScheduledTaskTrigger -AtStartup
# S4U runs without anyone logged in and without storing the Windows password.
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType S4U -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
Write-Output "task: '$taskName' registered (at startup, S4U)"

if ($Test) {
    wsl.exe --shutdown
    Start-Sleep -Seconds 5
    Start-ScheduledTask -TaskName $taskName
    Start-Sleep -Seconds 40
    $state = (Get-ScheduledTask -TaskName $taskName).State
    Write-Output "task state after start: $state"
    wsl.exe -d $Distro -- systemctl is-active opentakserver eud_handler_ssl nginx
}
