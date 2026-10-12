$ErrorActionPreference = 'Stop'
$stateFile = Join-Path $PSScriptRoot '.local\processes.json'
if (!(Test-Path -LiteralPath $stateFile)) {
    Write-Output 'No managed local services are running.'
    return
}

$state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
foreach ($record in $state.processes) {
    $process = Get-Process -Id $record.id -ErrorAction SilentlyContinue
    # Check the creation time as well as the PID to avoid stopping a reused PID.
    if ($process -and $process.StartTime.ToUniversalTime().Ticks.ToString() -eq $record.startTicks) {
        # Oracle javapath may start a child JVM; stop the managed process tree.
        & taskkill.exe /PID $process.Id /T /F | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not stop $($record.name)." }
        $process.WaitForExit(10000) | Out-Null
        Write-Output "Stopped $($record.name) (PID $($record.id))."
    }
}
Remove-Item -LiteralPath $stateFile
