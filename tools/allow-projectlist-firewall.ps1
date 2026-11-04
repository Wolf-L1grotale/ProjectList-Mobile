param(
    [int]$Port = 3000,
    [string]$ProgramPath = ""
)

$ErrorActionPreference = "Stop"

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "Run this script as Administrator (right-click -> Run with PowerShell as admin)." -ForegroundColor Yellow
    exit 1
}

function Remove-RuleIfExists([string]$Name) {
    Get-NetFirewallRule -DisplayName $Name -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule -ErrorAction SilentlyContinue
}

$portRule = "ProjectList LAN (TCP $Port)"
Remove-RuleIfExists $portRule
New-NetFirewallRule `
    -DisplayName $portRule `
    -Direction Inbound `
    -Action Allow `
    -Protocol TCP `
    -LocalPort $Port `
    -RemoteAddress LocalSubnet `
    -Profile Any `
    -Description "Allow ProjectList pairing from the local network" | Out-Null
Write-Host "Created rule: $portRule (TCP $Port, local subnet only)" -ForegroundColor Green

if (-not $ProgramPath) {
    $proc = Get-CimInstance Win32_Process -Filter "Name='project-list.exe'" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($proc -and $proc.ExecutablePath) {
        $ProgramPath = $proc.ExecutablePath
    }
}

if ($ProgramPath) {
    if (-not (Test-Path -LiteralPath $ProgramPath)) {
        Write-Host "Program not found: $ProgramPath" -ForegroundColor Yellow
    } else {
        $programRule = "ProjectList LAN (program)"
        Remove-RuleIfExists $programRule
        New-NetFirewallRule `
            -DisplayName $programRule `
            -Direction Inbound `
            -Action Allow `
            -Program $ProgramPath `
            -RemoteAddress LocalSubnet `
            -Profile Any `
            -Description "Allow ProjectList from the local network on any port" | Out-Null
        Write-Host "Created rule: $programRule" -ForegroundColor Green
        Write-Host "Program: $ProgramPath"
    }
} else {
    Write-Host "Running project-list.exe was not found. Only the TCP port rule was created." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Done. Make sure the phone is connected to the same local network." -ForegroundColor Green
