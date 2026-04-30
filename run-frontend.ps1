# Launch Copiloto AI Chat (Java 25)
$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot

try {
    if (-not (Test-Path "pom.xml")) {
        throw "pom.xml not found. Please run from the repository root."
    }

    Write-Host "Stopping any running Copiloto instances..." -ForegroundColor Yellow
    Get-Process -Name "java","javaw" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*Copiloto*" } |
        ForEach-Object { Stop-Process -Id $_.Id -Force }

    Write-Host "Building and launching Copiloto..." -ForegroundColor Green
    & mvn compile javafx:run -q
} catch {
    Write-Error $_.Exception.Message
} finally {
    Pop-Location
}
