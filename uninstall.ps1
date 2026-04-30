# Copiloto Uninstall Script
# Removes hooks from Claude Code settings

$ErrorActionPreference = "Stop"

Write-Host "=== Copiloto Uninstaller ===" -ForegroundColor Cyan
Write-Host ""

$SettingsPath = "$env:USERPROFILE\.claude\settings.json"

if (-not (Test-Path $SettingsPath)) {
    Write-Host "No settings.json found. Nothing to uninstall." -ForegroundColor Yellow
    exit 0
}

$Settings = Get-Content $SettingsPath -Raw | ConvertFrom-Json

if ($Settings.hooks) {
    # Remove Copiloto hooks
    if ($Settings.hooks.Stop) {
        $Settings.hooks.PSObject.Properties.Remove("Stop")
        Write-Host "Removed Stop hook" -ForegroundColor Gray
    }
    if ($Settings.hooks.PostToolUse) {
        $Settings.hooks.PSObject.Properties.Remove("PostToolUse")
        Write-Host "Removed PostToolUse hook" -ForegroundColor Gray
    }

    # Save settings
    $Settings | ConvertTo-Json -Depth 10 | Set-Content $SettingsPath -Encoding UTF8
    Write-Host ""
    Write-Host "Copiloto hooks removed successfully!" -ForegroundColor Green
} else {
    Write-Host "No hooks found in settings." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Note: The Copiloto folder has not been deleted." -ForegroundColor Gray
Write-Host "To fully remove, delete: $PSScriptRoot" -ForegroundColor Gray
Write-Host ""
