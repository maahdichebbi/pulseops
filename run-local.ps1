# run-local.ps1 — local dev shortcut
# Calls Maven directly (bypasses the mvnw.cmd wrapper which has Windows batch quirks)
# Usage: .\run-local.ps1 [any maven goals]
# Example: .\run-local.ps1 spring-boot:run

$maven = "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.16-bin\bin\mvn.cmd"

if (-not (Test-Path $maven)) {
    Write-Error "Maven not found at $maven. Run .\mvnw.cmd compile once to download it."
    exit 1
}

& $maven @args
