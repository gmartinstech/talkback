# TalkBack Installation Script for Windows
# Installs dependencies and configures Claude Code hooks

$ErrorActionPreference = "Stop"

Write-Host "=== TalkBack Windows Installer ===" -ForegroundColor Cyan
Write-Host ""

# Get script directory
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Write-Host "Project directory: $ScriptDir" -ForegroundColor Gray
Write-Host "Environment: Windows" -ForegroundColor Gray

# Step 1: Install edge-tts
Write-Host ""
Write-Host "[1/4] Installing edge-tts..." -ForegroundColor Yellow
try {
    pip install edge-tts --quiet 2>$null
    Write-Host "  edge-tts installed successfully" -ForegroundColor Green
} catch {
    Write-Host "  Warning: Failed to install edge-tts. Will use SAPI fallback." -ForegroundColor Yellow
}

# Step 2: Create temp directory
Write-Host ""
Write-Host "[2/4] Creating temp directory..." -ForegroundColor Yellow
if (-not (Test-Path "C:\temp")) {
    New-Item -ItemType Directory -Path "C:\temp" -Force | Out-Null
}
Write-Host "  Created C:\temp for audio files" -ForegroundColor Gray

# Step 3: Configure Claude Code hooks
Write-Host ""
Write-Host "[3/4] Configuring Claude Code hooks..." -ForegroundColor Yellow

$SettingsPath = "$env:USERPROFILE\.claude\settings.json"

# Load existing settings or create new
if (Test-Path $SettingsPath) {
    $Settings = Get-Content $SettingsPath -Raw | ConvertFrom-Json
    Write-Host "  Found existing settings.json" -ForegroundColor Gray

    # Backup existing settings
    Copy-Item $SettingsPath "$SettingsPath.backup" -Force
    Write-Host "  Backup created: settings.json.backup" -ForegroundColor Gray
} else {
    $Settings = @{}
    Write-Host "  Creating new settings.json" -ForegroundColor Gray
}

# Ensure hooks object exists
if (-not $Settings.hooks) {
    $Settings | Add-Member -NotePropertyName "hooks" -NotePropertyValue @{} -Force
}

# Define hook paths (using forward slashes for JSON)
$OnStopPath = ($ScriptDir -replace '\\', '/') + "/hooks/on_stop.py"
$OnToolPath = ($ScriptDir -replace '\\', '/') + "/hooks/on_tool_complete.py"

# Create Stop hook configuration
$StopHook = @{
    matcher = ""
    hooks = @(
        @{
            type = "command"
            command = "python `"$OnStopPath`""
            timeout = 30
        }
    )
}

# Create PostToolUse hook configuration
$PostToolHook = @{
    matcher = "*"
    hooks = @(
        @{
            type = "command"
            command = "python `"$OnToolPath`""
            timeout = 10
        }
    )
}

# Add hooks to settings
$Settings.hooks | Add-Member -NotePropertyName "Stop" -NotePropertyValue @($StopHook) -Force
$Settings.hooks | Add-Member -NotePropertyName "PostToolUse" -NotePropertyValue @($PostToolHook) -Force

# Ensure .claude directory exists
$ClaudeDir = "$env:USERPROFILE\.claude"
if (-not (Test-Path $ClaudeDir)) {
    New-Item -ItemType Directory -Path $ClaudeDir | Out-Null
}

# Save settings.json
$Settings | ConvertTo-Json -Depth 10 | Set-Content $SettingsPath -Encoding UTF8
Write-Host "  Saved to: $SettingsPath" -ForegroundColor Gray

# Step 4: Test TTS
Write-Host ""
Write-Host "[4/4] Testing TTS..." -ForegroundColor Yellow
try {
    $testResult = & python "$ScriptDir\speak.py" "TalkBack installed successfully" 2>&1
    Write-Host "  TTS test completed" -ForegroundColor Green
} catch {
    Write-Host "  TTS test failed - check configuration" -ForegroundColor Yellow
}

# Done
Write-Host ""
Write-Host "=== Installation Complete ===" -ForegroundColor Green
Write-Host ""
Write-Host "TalkBack hooks are now active!" -ForegroundColor Cyan
Write-Host ""
Write-Host "Configuration: $ScriptDir\config.json" -ForegroundColor Gray
Write-Host ""
Write-Host "Options:" -ForegroundColor White
Write-Host "  speak_responses: true   - Speak Claude's final responses"
Write-Host "  speak_thinking: false   - Announce what Claude is doing"
Write-Host "  speak_tool_results: false - Speak detailed tool outputs"
Write-Host ""
Write-Host "To test manually: python `"$ScriptDir\speak.py`" `"Hello world`"" -ForegroundColor Yellow
Write-Host ""
Write-Host "=== WSL Installation ===" -ForegroundColor Cyan
Write-Host "To install for WSL distros, run from within WSL:" -ForegroundColor White
$WslPath = "/mnt/" + ($ScriptDir.Substring(0,1).ToLower()) + ($ScriptDir.Substring(2) -replace '\\', '/')
Write-Host "  cd `"$WslPath`" && bash install-wsl.sh" -ForegroundColor Yellow
Write-Host ""
