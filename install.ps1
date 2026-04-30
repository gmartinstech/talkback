# Copiloto Installation Script for Windows
# Verifica dependencias, compila o projeto e cria atalhos

$ErrorActionPreference = "Stop"

Write-Host "=== Copiloto Windows Installer ===" -ForegroundColor Cyan
Write-Host ""

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Write-Host "Diretorio do projeto: $ScriptDir" -ForegroundColor Gray

# ============================================================================
# Verificar pre-requisitos
# ============================================================================

Write-Host ""
Write-Host "[1/5] Verificando pre-requisitos..." -ForegroundColor Yellow

# Java 25+
try {
    $javaVersion = & java -version 2>&1 | Select-String "version" | Select-Object -First 1
    if ($javaVersion -match '"(\d+)\.(\d+)') {
        $major = [int]$matches[1]
        if ($major -ge 25) {
            Write-Host "  [OK] Java $major instalado" -ForegroundColor Green
        } else {
            Write-Host "  [ERRO] Java $major encontrado, mas e necessario Java 25+" -ForegroundColor Red
            exit 1
        }
    } else {
        Write-Host "  [ERRO] Nao foi possivel determinar a versao do Java" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "  [ERRO] Java nao encontrado. Instale o Java 25+" -ForegroundColor Red
    exit 1
}

# Maven
try {
    $mvnVersion = & mvn -version 2>&1 | Select-Object -First 1
    Write-Host "  [OK] Maven encontrado: $mvnVersion" -ForegroundColor Green
} catch {
    Write-Host "  [ERRO] Maven nao encontrado. Instale o Apache Maven 3.9+" -ForegroundColor Red
    exit 1
}

# Python
try {
    $pyVersion = & py --version 2>&1
    Write-Host "  [OK] Python encontrado: $pyVersion" -ForegroundColor Green
} catch {
    Write-Host "  [ERRO] Python nao encontrado. Instale o Python 3.12+" -ForegroundColor Red
    exit 1
}

# edge-tts
try {
    $edgeVersion = & py -m pip show edge-tts 2>$null | Select-String "^Version:" | ForEach-Object { $_.ToString().Split(':')[1].Trim() }
    if ($edgeVersion) {
        Write-Host "  [OK] edge-tts $edgeVersion instalado" -ForegroundColor Green
    } else {
        throw "edge-tts nao instalado"
    }
} catch {
    Write-Host "  -> Instalando edge-tts..." -ForegroundColor Yellow
    & py -m pip install edge-tts --quiet
    Write-Host "  [OK] edge-tts instalado" -ForegroundColor Green
}

# ============================================================================
# Compilar o projeto
# ============================================================================

Write-Host ""
Write-Host "[2/5] Compilando Copiloto..." -ForegroundColor Yellow
Push-Location $ScriptDir
try {
    & mvn clean package -DskipTests -q
    if ($LASTEXITCODE -ne 0) {
        throw "Falha na compilacao"
    }
    Write-Host "  [OK] Compilacao concluida" -ForegroundColor Green
} finally {
    Pop-Location
}

# ============================================================================
# Criar diretorio de instalacao
# ============================================================================

Write-Host ""
Write-Host "[3/5] Criando diretorio de instalacao..." -ForegroundColor Yellow

$InstallDir = "$env:USERPROFILE\Copiloto"
if (-not (Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
}

# Copiar JAR (fat jar, excluindo original-*)
$JarSource = Get-ChildItem -Path "$ScriptDir\target" -Filter "copiloto-*.jar" | Where-Object { $_.Name -notlike "original-*" } | Select-Object -First 1
if ($JarSource) {
    Copy-Item $JarSource.FullName "$InstallDir\copiloto.jar" -Force
    $sizeMB = [math]::Round($JarSource.Length/1MB,2)
    Write-Host "  [OK] JAR copiado para $InstallDir\copiloto.jar (${sizeMB} MB)" -ForegroundColor Green
} else {
    Write-Host "  [ERRO] JAR nao encontrado em target/" -ForegroundColor Red
    exit 1
}

# Copiar config e scripts auxiliares
Copy-Item "$ScriptDir\config.json" "$InstallDir\config.json" -Force -ErrorAction SilentlyContinue
Copy-Item "$ScriptDir\speak.py" "$InstallDir\speak.py" -Force
Copy-Item "$ScriptDir\hooks" "$InstallDir\hooks" -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "  [OK] Arquivos auxiliares copiados" -ForegroundColor Green

# ============================================================================
# Criar script de lancamento
# ============================================================================

Write-Host ""
Write-Host "[4/5] Criando script de lancamento..." -ForegroundColor Yellow

$LauncherContent = @"
@echo off
REM Copiloto Launcher
set JAVA_HOME=$env:JAVA_HOME
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d "$InstallDir"
java --enable-preview --enable-native-access=ALL-UNNAMED -cp "$InstallDir\copiloto.jar" net.martinstech.copiloto.CopilotoLauncher
"@

Set-Content -Path "$InstallDir\Copiloto.bat" -Value $LauncherContent -Encoding UTF8
Write-Host "  [OK] Launcher criado: $InstallDir\Copiloto.bat" -ForegroundColor Green

# Criar atalho na area de trabalho
$WshShell = New-Object -ComObject WScript.Shell
$DesktopPath = [Environment]::GetFolderPath("Desktop")
$Shortcut = $WshShell.CreateShortcut("$DesktopPath\Copiloto.lnk")
$Shortcut.TargetPath = "$InstallDir\Copiloto.bat"
$Shortcut.WorkingDirectory = "$InstallDir"
$Shortcut.IconLocation = "javaw.exe,0"
$Shortcut.Description = "Copiloto - Assistente de IA"
$Shortcut.Save()
Write-Host "  [OK] Atalho criado na area de trabalho" -ForegroundColor Green

# ============================================================================
# Configurar hooks do Claude Code (opcional)
# ============================================================================

Write-Host ""
Write-Host "[5/5] Configurando hooks do Claude Code..." -ForegroundColor Yellow

$SettingsPath = "$env:USERPROFILE\.claude\settings.json"

if (Test-Path $SettingsPath) {
    $Settings = Get-Content $SettingsPath -Raw | ConvertFrom-Json
    Copy-Item $SettingsPath "$SettingsPath.backup" -Force -ErrorAction SilentlyContinue
} else {
    $Settings = @{}
    $ClaudeDir = "$env:USERPROFILE\.claude"
    if (-not (Test-Path $ClaudeDir)) {
        New-Item -ItemType Directory -Path $ClaudeDir | Out-Null
    }
}

if (-not $Settings.hooks) {
    $Settings | Add-Member -NotePropertyName "hooks" -NotePropertyValue @{} -Force
}

$OnStopPath = ($InstallDir -replace '\\', '/') + "/hooks/on_stop.py"
$OnToolPath = ($InstallDir -replace '\\', '/') + "/hooks/on_tool_complete.py"

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

$Settings.hooks | Add-Member -NotePropertyName "Stop" -NotePropertyValue @($StopHook) -Force
$Settings.hooks | Add-Member -NotePropertyName "PostToolUse" -NotePropertyValue @($PostToolHook) -Force

$Settings | ConvertTo-Json -Depth 10 | Set-Content $SettingsPath -Encoding UTF8
Write-Host "  [OK] Hooks configurados em $SettingsPath" -ForegroundColor Green

# ============================================================================
# Finalizacao
# ============================================================================

Write-Host ""
Write-Host "=== Instalacao Concluida ===" -ForegroundColor Green
Write-Host ""
Write-Host "Copiloto esta pronto para usar!" -ForegroundColor Cyan
Write-Host ""
Write-Host "Para iniciar:" -ForegroundColor White
Write-Host "  - Duplo-clique no atalho da area de trabalho" -ForegroundColor Gray
Write-Host "  - Ou execute: $InstallDir\Copiloto.bat" -ForegroundColor Gray
Write-Host ""
Write-Host "Para compilar do codigo-fonte:" -ForegroundColor White
Write-Host "  cd `"$ScriptDir`" ; mvn javafx:run" -ForegroundColor Gray
Write-Host ""
Write-Host "Para desinstalar:" -ForegroundColor White
Write-Host "  1. Delete a pasta: $InstallDir" -ForegroundColor Gray
Write-Host "  2. Delete o atalho da area de trabalho" -ForegroundColor Gray
Write-Host "  3. Execute: .\uninstall.ps1" -ForegroundColor Gray
Write-Host ""
