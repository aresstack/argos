param([switch]$WithCamoufox)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (-not (Test-Path $java)) { throw 'Set JAVA_HOME to a Java 21 installation.' }
$artifact = Join-Path $project 'build/libs/argos.jar'
if (-not (Test-Path $artifact)) { throw 'Standalone Argos JAR missing. Run gradlew bootJar first.' }

$smokeRoot = if ($env:ARGOS_SMOKE_ROOT) { $env:ARGOS_SMOKE_ROOT } else { Join-Path $project 'build' }
New-Item -ItemType Directory -Force -Path $smokeRoot | Out-Null
$work = Join-Path $smokeRoot ('standalone-smoke-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
Copy-Item $artifact (Join-Path $work 'argos.jar')
if (Test-Path (Join-Path $work 'lib')) { throw 'The smoke must not use external lib/ files.' }

$portListener = [System.Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$portListener.Start()
$port = $portListener.LocalEndpoint.Port
$portListener.Stop()
$env:SERVER_PORT = [string]$port
$env:SECURITY_MODE = 'none' # isolated loopback smoke test only; production uses central-oauth
$env:ARGOS_PROFILE_DIR = Join-Path $work 'profile'
$env:ARGOS_HEADLESS = 'true'
$backend = $null
$website = $null
$url = 'http://127.0.0.1:' + $port + '/'
try {
    $backend = Start-Process -FilePath $java -ArgumentList @('-jar', 'argos.jar') -WorkingDirectory $work -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $work 'out.log') -RedirectStandardError (Join-Path $work 'err.log')
    $healthy = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Milliseconds 500
        try {
            $reply = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $port + '/health') -TimeoutSec 2
            if ($reply.status -eq 'UP') { $healthy = $true; break }
        } catch {}
    }
    if (-not $healthy) {
        Get-Content (Join-Path $work 'err.log') -Tail 30 -ErrorAction SilentlyContinue
        throw 'Standalone health check failed.'
    }
    Write-Output 'STANDALONE_HEALTH_OK'

    # Pyloros-compatible discovery: GET / must explicitly return 405, not 400,
    # 404, or an empty SSE stream. POST / remains the MCP transport.
    $rootGetStatus = 0
    try {
        $rootGet = Invoke-WebRequest -UseBasicParsing -Uri ('http://127.0.0.1:' + $port + '/') -Headers @{ Accept = 'text/event-stream' } -TimeoutSec 6
        $rootGetStatus = [int]$rootGet.StatusCode
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) { $rootGetStatus = [int]$_.Exception.Response.StatusCode } else { throw }
    }
    if ($rootGetStatus -ne 405) { throw ('MCP GET / must return 405 like Pyloros, got ' + $rootGetStatus) }
    Write-Output 'PYLOROS_ROOT_GET_405_OK'

    $headers = @{ Accept = 'application/json, text/event-stream'; 'Content-Type' = 'application/json' }
    $initialize = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}'
    $reply = Invoke-WebRequest -UseBasicParsing -Uri $url -Method POST -Headers $headers -Body $initialize -TimeoutSec 15
    if ($reply.Content -notmatch '"serverInfo"') { throw 'MCP initialization failed.' }
    $session = [string](@($reply.Headers['mcp-session-id'])[0])
    if ([string]::IsNullOrWhiteSpace($session)) { throw 'MCP session ID missing.' }
    $headers['Mcp-Session-Id'] = $session
    [void](Invoke-WebRequest -UseBasicParsing -Uri $url -Method POST -Headers $headers -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' -TimeoutSec 10)
    function Call-Argos([string]$body, [int]$seconds = 30) {
        $result = Invoke-WebRequest -UseBasicParsing -Uri $url -Method POST -Headers $headers -Body $body -TimeoutSec $seconds
        $text = [string]$result.Content
        if ($text -match '(?m)^data: (\{[^\r\n]+\})') { $text = $Matches[1] }
        $parsed = $text | ConvertFrom-Json
        if ($parsed.result.isError) { throw ('Argos tool error: ' + $parsed.result.content[0].text) }
        return $parsed
    }
    $tools = Call-Argos '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
    if (@($tools.result.tools).Count -ne 9) { throw 'Expected nine MCP tools.' }
    Write-Output 'STANDALONE_MCP_TOOLS_OK'

    if ($WithCamoufox) {
        $sitePort = 18087
        $siteDir = Join-Path $work 'site'
        New-Item -ItemType Directory -Force -Path $siteDir | Out-Null
        [IO.File]::WriteAllText((Join-Path $siteDir 'index.html'), '<html><head><title>Argos Independent Jar</title></head><body><button id="check" onclick="document.querySelector(''body'').setAttribute(''data-clicked'',''yes'')">Click</button></body></html>', [Text.UTF8Encoding]::new($false))
        $website = Start-Process -FilePath 'python' -ArgumentList "-m http.server $sitePort --bind 127.0.0.1" -WorkingDirectory $siteDir -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $work 'web-out.log') -RedirectStandardError (Join-Path $work 'web-err.log')
        $navigation = Call-Argos ('{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"browser_navigate","arguments":{"url":"http://127.0.0.1:' + $sitePort + '/index.html"}}}') 85
        $page = $navigation.result.content[0].text | ConvertFrom-Json
        if ($page.title -ne 'Argos Independent Jar') { throw 'Camoufox navigation failed.' }
        Write-Output 'STANDALONE_CAMOUFOX_NAVIGATION_OK'
        [void](Call-Argos '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"browser_click","arguments":{"selector":"#check"}}}')
        $state = Call-Argos '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"browser_evaluate","arguments":{"expression":"() => document.body.dataset.clicked"}}}'
        if (($state.result.content[0].text | ConvertFrom-Json) -ne 'yes') { throw 'Camoufox interaction failed.' }
        Write-Output 'STANDALONE_CAMOUFOX_INTERACTION_OK'
    }

    Write-Output 'STANDALONE_JAR_ACCEPTED'
} finally {
    if ($backend -and -not $backend.HasExited) {
        Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
    }
    if ($website -and -not $website.HasExited) {
        Stop-Process -Id $website.Id -Force -ErrorAction SilentlyContinue
    }
    if ($WithCamoufox) {
        Get-CimInstance Win32_Process -Filter "name='camoufox.exe'" |
            Where-Object { $_.CommandLine -and $_.CommandLine.Contains($env:ARGOS_PROFILE_DIR) } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    }
    Write-Output ('SMOKE_LOGS=' + $work)
}
