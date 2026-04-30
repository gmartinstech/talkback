# Copiloto Uninstall Script
# Removes hooks, instalation folder and desktop shortcut

$ErrorActionPreference = "Stop"

Write-Host "=== Copiloto Uninstaller ===" -ForegroundColor Cyan
Write-Host ""

$InstallDir = "$env:USERPROFILE\Copiloto"
$DesktopPath = [Environment]::GetFolderPath("Desktop")
$ShortcutPath = "$DesktopPath\Copiloto.lnk"

# Remove hooks from Claude Code settings
$SettingsPath = "$env:USERPROFILE\.claude\settings.json"

if (Test-Path $SettingsPath) {
    $Settings = Get-Content $SettingsPath -Raw | ConvertFrom-Json

    if ($Settings.hooks) {
        if ($Settings.hooks.Stop) {
            $Settings.hooks.PSObject.Properties.Remove("Stop")
            Write-Host "  ✓ Removido hook Stop" -ForegroundColor Green
        }
        if ($Settings.hooks.PostToolUse) {
            $Settings.hooks.PSObject.Properties.Remove("PostToolUse")
            Write-Host "  ✓ Removido hook PostToolUse" -ForegroundColor Green
        }
        $Settings | ConvertTo-Json -Depth 10 | Set-Content $SettingsPath -Encoding UTF8
    }
}

# Remove desktop shortcut
if (Test-Path $ShortcutPath) {
    Remove-Item $ShortcutPath -Force
    Write-Host "  ✓ Atalho da área de trabalho removido" -ForegroundColor Green
}

# Remove installation directory
if (Test-Path $InstallDir) {
    Remove-Item $InstallDir -Recurse -Force
    Write-Host "  ✓ Pasta de instalação removida: $InstallDir" -ForegroundColor Green
}

Write-Host ""
Write-Host "=== Desinstalação Concluída ===" -ForegroundColor Green
Write-Host ""
Write-Host "O diretório do código-fonte em $PSScriptRoot não foi removido." -ForegroundColor Gray
Write-Host ""
