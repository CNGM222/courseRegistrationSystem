param(
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$runtimeDirectory = Join-Path $projectRoot '.local'
$stateFile = Join-Path $runtimeDirectory 'processes.json'
$configFile = Join-Path $runtimeDirectory 'backend.properties'
$jarFile = Join-Path $projectRoot 'backend\target\backend-0.0.1-SNAPSHOT.jar'
$viteFile = Join-Path $projectRoot 'frontend\node_modules\vite\bin\vite.js'
$frontendUrl = "http://127.0.0.1:$FrontendPort"

function Test-ManagedProcess($record) {
    $process = Get-Process -Id $record.id -ErrorAction SilentlyContinue
    return $process -and $process.StartTime.ToUniversalTime().Ticks.ToString() -eq $record.startTicks
}

if (Test-Path -LiteralPath $stateFile) {
    $state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
    $live = @($state.processes | Where-Object { Test-ManagedProcess $_ })
    if ($live.Count -eq 2) {
        if ($Build) { throw 'Stop the services with .\stop-local.ps1 before rebuilding.' }
        Write-Output "Services are already running: $($state.url)"
        exit 0
    }
    & (Join-Path $projectRoot 'stop-local.ps1')
}

if ($BackendPort -lt 1 -or $BackendPort -gt 65535 -or
    $FrontendPort -lt 1 -or $FrontendPort -gt 65535 -or $BackendPort -eq $FrontendPort) {
    throw 'Specify two different TCP ports between 1 and 65535.'
}
$listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
foreach ($port in @($BackendPort, $FrontendPort)) {
    if ($listeners.Port -contains $port) { throw "Port $port is already in use." }
}

if ($Build) {
    Push-Location (Join-Path $projectRoot 'backend')
    try {
        & .\mvnw.cmd -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    } finally { Pop-Location }
    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
}

foreach ($file in @($jarFile, $viteFile, (Join-Path $projectRoot 'frontend\dist\index.html'))) {
    if (!(Test-Path -LiteralPath $file)) { throw "Missing $file. Install dependencies and run .\start-local.ps1 -Build." }
}
$javaExecutable = (Get-Command java.exe -ErrorAction Stop).Source
$nodeExecutable = (Get-Command node.exe -ErrorAction Stop).Source
New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null

# Keep this key stable across restarts so encrypted identifiers remain readable.
# For an existing database with encrypted identifiers, supply the original SSN_KEY.
if (!(Test-Path -LiteralPath $configFile)) {
    $keyBytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($keyBytes) } finally { $random.Dispose() }
    $key = ([BitConverter]::ToString($keyBytes)).Replace('-', '').ToLowerInvariant()
    $settings = 'app.ssn-key=${SSN_KEY:' + $key + "}`r`n" +
        'app.billing.dispatch-enabled=${BILLING_DISPATCH_ENABLED:false}' + "`r`n"
    [System.IO.File]::WriteAllText($configFile, $settings, (New-Object System.Text.UTF8Encoding($false)))
}

function Wait-ForHttp([string]$Url, $Process) {
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $deadline) {
        $Process.Refresh()
        if ($Process.HasExited) { throw "Service exited. See logs in $runtimeDirectory." }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "Service did not become ready at $Url. See logs in $runtimeDirectory."
}

$started = @()
$previousProxy = $env:VITE_API_PROXY_TARGET
try {
    $configUri = ([Uri]$configFile).AbsoluteUri
    $backend = Start-Process -FilePath $javaExecutable -ArgumentList @(
        '-jar', ('"' + $jarFile + '"'),
        ('"--spring.config.additional-location=' + $configUri + '"'),
        '--server.address=127.0.0.1', "--server.port=$BackendPort", '--debug=false'
    ) -WorkingDirectory (Join-Path $projectRoot 'backend') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runtimeDirectory 'backend.log') `
        -RedirectStandardError (Join-Path $runtimeDirectory 'backend-error.log')
    $started += $backend
    Wait-ForHttp "http://127.0.0.1:$BackendPort/api/v1/auth/csrf" $backend

    $env:VITE_API_PROXY_TARGET = "http://127.0.0.1:$BackendPort"
    $frontend = Start-Process -FilePath $nodeExecutable -ArgumentList @(
        ('"' + $viteFile + '"'), 'preview', '--host', '127.0.0.1',
        '--port', "$FrontendPort", '--strictPort'
    ) -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runtimeDirectory 'frontend.log') `
        -RedirectStandardError (Join-Path $runtimeDirectory 'frontend-error.log')
    $started += $frontend
    Wait-ForHttp "$frontendUrl/api/v1/auth/csrf" $frontend

    $records = @(
        @{ name = 'backend'; id = $backend.Id; startTicks = $backend.StartTime.ToUniversalTime().Ticks.ToString() },
        @{ name = 'frontend'; id = $frontend.Id; startTicks = $frontend.StartTime.ToUniversalTime().Ticks.ToString() }
    )
    @{ url = $frontendUrl; processes = $records } | ConvertTo-Json -Depth 4 |
        Set-Content -Encoding UTF8 -LiteralPath $stateFile
    Write-Output "Frontend: $frontendUrl"
    Write-Output "Backend:  http://127.0.0.1:$BackendPort/api/v1"
    Write-Output "Logs:     $runtimeDirectory"
    Write-Output 'Stop:     .\stop-local.ps1'
} catch {
    foreach ($process in $started) {
        $process.Refresh()
        if (!$process.HasExited) {
            & taskkill.exe /PID $process.Id /T /F | Out-Null
        }
    }
    throw
} finally {
    $env:VITE_API_PROXY_TARGET = $previousProxy
}
