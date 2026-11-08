# ==============================================================================
# Simulation: agent-card supportedInterfaces resolution + Hub task dispatch.
#
# Reproduces the reported bug scenario end-to-end WITHOUT Docker / Postgres:
#
#   1. Starts weather-agent (:8081) and calculator-agent (:8082) locally in mock mode.
#   2. Acts as the Hub registration read path  -> GET /.well-known/agent-card.json
#      and extracts supportedInterfaces[0].url (this is what TaskService uses as
#      the dispatch endpoint).
#   3. Proves the old hardcoded URLs (weather-agent:8081 / calculator-agent:8082)
#      are unresolvable outside Docker Compose (UnknownHostException symptom).
#   4. Dispatches a task to the RESOLVED endpoint using the exact headers the
#      Hub sends (X-Correlation-Id, X-Context-Id, X-Task-Id, traceparent).
#   5. Verifies the A2A_AGENT_BASE_URL override pinning mode.
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\simulate-agent-card-endpoint.ps1
# ==============================================================================

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repoRoot = Split-Path -Parent $PSScriptRoot
$agents = @(
    @{ name = 'weather';   module = 'weather-agent';   port = 8081; query = 'What is the weather in Bogota tomorrow?';  oldUrlHostname = 'weather-agent:8081' },
    @{ name = 'calculator'; module = 'calculator-agent'; port = 8082; query = 'Calculate 17 * 23 + 4';                  oldUrlHostname = 'calculator-agent:8082' }
)

$script:passCount = 0
$script:failCount = 0
function Check($name, $condition, $detail) {
    if ($condition) {
        $script:passCount++
        Write-Host "  [PASS] $name $detail" -ForegroundColor Green
    } else {
        $script:failCount++
        Write-Host "  [FAIL] $name $detail" -ForegroundColor Red
    }
}

function New-HubHeaders {
    $traceId = ([guid]::NewGuid().ToString('N'))
    return @{
        'X-Correlation-Id' = [guid]::NewGuid().ToString()
        'X-Context-Id'     = "ctx-sim-$(Get-Random -Maximum 9999)"
        'X-Task-Id'        = [guid]::NewGuid().ToString()
        'traceparent'      = "00-$traceId-$($traceId.Substring(0, 16))-01"
    }
}

# ------------------------------------------------------------------------------
# STEP 1: Start both agents locally (mock mode, no GEMINI_API_KEY required).
# ------------------------------------------------------------------------------
Write-Host "`n=== STEP 1: Boot agents locally (registered by the Hub at http://localhost:<port>) ==="
$procs = @()
foreach ($a in $agents) {
    $jar = Join-Path $repoRoot "$($a.module)\target\$($a.module)-0.1.0-alpha.jar"
    if (-not (Test-Path $jar)) { throw "Agent jar not found: $jar (build first)" }
    Write-Host "  Starting $($a.module) on port $($a.port) (mock mode)"
    $logFile = Join-Path $env:TEMP "a2a-$($a.name)-sim.log"
    $proc = Start-Process -FilePath 'java' `
        -ArgumentList @("-jar", $jar, "--server.port=$($a.port)", `
                        "--spring.ai.google.genai.api-key=mock-dev-key", "--gemini.api-key=mock-dev-key", `
                        "--spring.main.banner-mode=off") `
        -WorkingDirectory (Join-Path $repoRoot $a.module) `
        -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" `
        -PassThru -WindowStyle Hidden
    $procs += @{ agent = $a; process = $proc; log = $logFile }
}

# calculator-agent reads gemini.api-key (not spring.ai.*); blank key triggers mock mode for it.
try {
    # ------------------------------------------------------------------------------
    # STEP 2: Hub registration read path - fetch the agent card on localhost.
    # ------------------------------------------------------------------------------
    Write-Host "`n=== STEP 2: Hub registers agent at http://localhost:<port> and reads its Agent Card ==="
    $results = @{}
    foreach ($h in $procs) {
        $a = $h.agent
        $registeredUrl = "http://localhost:$($a.port)"
        $card = $null
        for ($i = 0; $i -lt 45; $i++) {
            Start-Sleep -Seconds 2
            try {
                $card = Invoke-RestMethod -Uri "$registeredUrl/.well-known/agent-card.json" -Method Get -TimeoutSec 5
                break
            } catch { }
        }
        if (-not $card) { throw "$($a.module) did not become healthy on $registeredUrl. Log: $($h.log)" }
        $resolvedUrl = $card.supportedInterfaces[0].url
        $results[$a.name] = @{ card = $card; resolvedUrl = $resolvedUrl; registeredUrl = $registeredUrl }
        Write-Host "  [$($a.name)] registered='$registeredUrl'  card advertised endpoint='$resolvedUrl'"
        Check "$($a.name): card advertises the fetch address, not the container hostname" `
            ($resolvedUrl -eq "$registeredUrl/tasks") "(expected '$registeredUrl/tasks', got '$resolvedUrl')"
        Check "$($a.name): card has no hardcoded container hostname" `
            (-not ($resolvedUrl -like "*$($a.oldUrlHostname)*")) "(resolved='$resolvedUrl', banned-host='$($a.oldUrlHostname)')"
    }

    # ------------------------------------------------------------------------------
    # STEP 3: Reproduce the pre-fix symptom - the hardcoded hostnames do NOT resolve.
    # ------------------------------------------------------------------------------
    Write-Host "`n=== STEP 3: Confirm the OLD endpoints (pre-fix card content) would fail with UnknownHostException ==="
    foreach ($h in $procs) {
        $a = $h.agent
        $hostname = ($a.oldUrlHostname -split ':')[0]
        $dnsOk = $false
        try { $dnsOk = $null -ne (Resolve-DnsName $hostname -ErrorAction Stop) } catch { $dnsOk = $false }
        try {
            Invoke-RestMethod -Uri "http://$($a.oldUrlHostname)/.well-known/agent-card.json" -TimeoutSec 4 | Out-Null
            $reachable = $true
        } catch {
            # Any failure here (DNS resolution, connection refusal, timeout) means unreachable.
            $reachable = $false
        }
        Check "$($a.name): '$($a.oldUrlHostname)' is unresolvable here (would make Hub dispatch fail)" `
            ($DnsOk -eq $false -and $reachable -eq $false)
    }

    # ------------------------------------------------------------------------------
    # STEP 4: Hub dispatch - POST to the RESOLVED endpoint with the Hub's headers.
    # ------------------------------------------------------------------------------
    Write-Host "`n=== STEP 4: TaskService dispatch to resolved endpoint with Hub tracing headers ==="
    foreach ($h in $procs) {
        $a = $h.agent
        $r = $results[$a.name]
        $headers = New-HubHeaders
        $body = @{ query = $a.query; contextId = $headers['X-Context-Id'] } | ConvertTo-Json
        try {
            $resp = Invoke-RestMethod -Uri $r.resolvedUrl -Method Post -ContentType 'application/json' `
                -Headers $headers -Body $body -TimeoutSec 10
            Write-Host "  [$($a.name)] POST $($r.resolvedUrl) -> status='$($resp.status)' result='$($resp.result)'"
            Check "$($a.name): dispatch returned ok" ($resp.status -eq 'ok')
            Check "$($a.name): contextId echoed back" ($resp.contextId -eq $headers['X-Context-Id'])
        } catch {
            Check "$($a.name): dispatch to $($r.resolvedUrl) failed: $($_.Exception.Message)" $false
        }
    }

    # ------------------------------------------------------------------------------
    # STEP 5: A2A_AGENT_BASE_URL override - pinned endpoint wins derivation.
    # ------------------------------------------------------------------------------
    Write-Host "`n=== STEP 5: A2A_AGENT_BASE_URL (a2a.agent.base-url) override pins the advertised endpoint ==="
    $overrideBase = 'http://override-agent.internal:9443'
    $weatherJar = Join-Path $repoRoot "weather-agent\target\weather-agent-0.1.0-alpha.jar"
    $overrideLog = Join-Path $env:TEMP 'a2a-weather-override-sim.log'
    $overrideProc = Start-Process -FilePath 'java' `
        -ArgumentList @("-jar", $weatherJar, "--server.port=8083", "--a2a.agent.base-url=$overrideBase", `
                        "--spring.ai.google.genai.api-key=mock-dev-key", "--spring.main.banner-mode=off") `
        -WorkingDirectory (Join-Path $repoRoot 'weather-agent') `
        -RedirectStandardOutput $overrideLog -RedirectStandardError "$overrideLog.err" `
        -PassThru -WindowStyle Hidden
    try {
        $card = $null
        for ($i = 0; $i -lt 45; $i++) {
            Start-Sleep -Seconds 2
            try {
                $card = Invoke-RestMethod -Uri 'http://localhost:8083/.well-known/agent-card.json' -Method Get -TimeoutSec 5
                break
            } catch { }
        }
        if ($card) {
            $resolvedUrl = $card.supportedInterfaces[0].url
            Write-Host "  [weather@8083] A2A_AGENT_BASE_URL=$overrideBase -> advertised='$resolvedUrl'"
            Check "override pins advertised endpoint" ($resolvedUrl -eq "$overrideBase/tasks") "(got '$resolvedUrl')"
        } else {
            Check "override agent never became healthy on :8083" $false
        }
    } finally {
        if ($overrideProc -and -not $overrideProc.HasExited) { Stop-Process -Id $overrideProc.Id -Force -ErrorAction SilentlyContinue }
    }
} finally {
    # ------------------------------------------------------------------------------
    # Cleanup: stop all booted agents.
    # ------------------------------------------------------------------------------
    Write-Host "`n=== Cleanup: stopping simulated agents ==="
    foreach ($h in $procs) {
        if ($h.process -and -not $h.process.HasExited) { Stop-Process -Id $h.process.Id -Force -ErrorAction SilentlyContinue }
    }
}

Write-Host "`n=== SIMULATION SUMMARY: $($script:passCount) passed, $($script:failCount) failed ==="
if ($script:failCount -gt 0) { exit 1 }
exit 0
