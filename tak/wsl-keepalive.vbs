' Keeps the OpenTAKServer WSL2 distro running without a visible window (WSL stops a distro with no attached process).
' Copy to shell:startup so it starts at logon. Edit the distro name if yours differs.
CreateObject("WScript.Shell").Run "wsl.exe -d Ubuntu-24.04 --exec sleep infinity", 0, False
