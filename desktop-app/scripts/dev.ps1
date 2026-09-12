$ErrorActionPreference = "Stop"

$desktopDir = Split-Path -Parent $PSScriptRoot
$workspaceDir = Split-Path -Parent $desktopDir
$backendPort = 8080
$healthUrl = "http://127.0.0.1:$backendPort/api/auth/capabilities"
$startedBackend = $null

function Test-BackendReady {
    try {
        $response = Invoke-RestMethod -Method Get -Uri $healthUrl -TimeoutSec 2
        return $response.authentication -eq $true
    } catch {
        return $false
    }
}

try {
    if (-not (Test-BackendReady)) {
        $existingListener = Get-NetTCPConnection -LocalPort $backendPort -State Listen -ErrorAction SilentlyContinue
        if ($existingListener) {
            throw "Port $backendPort is occupied by a backend without the current authentication API. Stop that old backend and restart the desktop app."
        }
        Write-Host "Building Spring Boot backend..."
        Push-Location $workspaceDir
        try {
            & cmd /c ".\mvnw.cmd -q -DskipTests package"
            if ($LASTEXITCODE -ne 0) { throw "Backend build failed with exit code $LASTEXITCODE" }
        } finally {
            Pop-Location
        }

        $jar = Get-ChildItem -LiteralPath (Join-Path $workspaceDir "target") -Filter "wechat-bot-*.jar" |
            Where-Object { $_.Name -notlike "*.original" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if (-not $jar) { throw "Backend jar was not produced" }

        $logDir = Join-Path $workspaceDir "logs"
        New-Item -ItemType Directory -Path $logDir -Force | Out-Null
        $startedBackend = Start-Process -FilePath "java" -ArgumentList @(
            "-jar", $jar.FullName,
            "--server.port=$backendPort",
            "--app.cli.enabled=false"
        ) -WorkingDirectory $workspaceDir -WindowStyle Hidden -PassThru

        $deadline = (Get-Date).AddSeconds(90)
        while ((Get-Date) -lt $deadline -and -not (Test-BackendReady)) {
            if ($startedBackend.HasExited) { throw "Backend exited during startup with code $($startedBackend.ExitCode)" }
            Start-Sleep -Milliseconds 500
        }
        if (-not (Test-BackendReady)) { throw "Backend did not become ready at $healthUrl" }
    }

    $env:VITE_API_BASE_URL = "http://127.0.0.1:$backendPort"
    Set-Location $desktopDir
    & (Join-Path $desktopDir "node_modules\.bin\vite.cmd")
    exit $LASTEXITCODE
} finally {
    if ($startedBackend -and -not $startedBackend.HasExited) {
        Stop-Process -Id $startedBackend.Id -ErrorAction SilentlyContinue
    }
}
