# Launch TalkBack PR Reviewer (Java 25)
$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
if (Test-Path "pom.xml") {
    if (Test-Path "mvnw.cmd") {
        & .\mvnw.cmd javafx:run
    } else {
        & mvn javafx:run
    }
} else {
    Write-Error "pom.xml not found. Please run from the repository root."
}
Pop-Location
