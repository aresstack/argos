$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$jar = Join-Path $root 'build/package/argos.jar'
if (-not (Test-Path $java) -or -not (Test-Path $jar)) { throw 'Build Argos with Java 21 and packageArgos before testing.' }

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$env:SERVER_PORT = [string]$port
$env:SERVER_HOST = '127.0.0.1'
$env:SECURITY_MODE = 'central-oauth'
$env:REQUIRED_SCOPE = 'argos'
$env:PUBLIC_ORIGIN = 'https://argos.aresstack.de'
$env:CENTRAL_OAUTH_ISSUER = 'https://argos.aresstack.de'
$env:ARGOS_PROFILE_DIR = Join-Path $root 'build/central-auth-smoke-profile'
$log = Join-Path $root 'build/central-auth-smoke.log'
$errorLog = Join-Path $root 'build/central-auth-smoke.err.log'
$process = $null

function Status-For([string] $url, [string] $body, [string] $authorization) {
    $headers = @{ Accept = 'application/json, text/event-stream' }
    if ($authorization) { $headers['Authorization'] = $authorization }
    try {
        $reply = Invoke-WebRequest -UseBasicParsing -Uri $url -Method POST -ContentType 'application/json' -Headers $headers -Body $body -TimeoutSec 10
        return @{ Code = [int]$reply.StatusCode; Challenge = [string]$reply.Headers['WWW-Authenticate'] }
    } catch [System.Net.WebException] {
        $reply = $_.Exception.Response
        if ($null -eq $reply) { throw }
        return @{ Code = [int]$reply.StatusCode; Challenge = [string]$reply.Headers['WWW-Authenticate'] }
    }
}

try {
    $process = Start-Process -FilePath $java -ArgumentList @('-jar', ('"' + $jar + '"')) -WorkingDirectory $root -PassThru -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $errorLog
    $healthy = $false
    for ($i=0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $status = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $port + '/health') -TimeoutSec 2
            if ($status.status -eq 'UP') { $healthy = $true; break }
        } catch { }
    }
    if (-not $healthy) {
        Get-Content $errorLog -Tail 35 -ErrorAction SilentlyContinue
        throw 'SERVER_PORT startup/health check failed.'
    }
    Write-Output ('SERVER_PORT_ENV_OK=' + $port)
    $metadata = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $port + '/.well-known/oauth-protected-resource') -TimeoutSec 5
    if ($metadata.resource -ne 'https://argos.aresstack.de/' -or
            $metadata.authorization_servers[0] -ne 'https://argos.aresstack.de' -or
            $metadata.scopes_supported[0] -ne 'argos') {
        throw 'PUBLIC_ORIGIN, CENTRAL_OAUTH_ISSUER or REQUIRED_SCOPE did not reach OAuth metadata.'
    }
    Write-Output 'OAUTH_ENV_METADATA_OK'
    $url = 'http://127.0.0.1:' + $port + '/'
    $initialization = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"oauth-test","version":"1"}}}'
    $unauthorized = Status-For $url $initialization $null
    if ($unauthorized.Code -ne 401 -or $unauthorized.Challenge -notmatch 'oauth-protected-resource') {
        throw ('SECURITY_MODE did not protect MCP: ' + ($unauthorized | ConvertTo-Json -Compress))
    }
    Write-Output 'MCP_MISSING_TOKEN_DENIED_OK'
    $invalid = Status-For $url $initialization 'Bearer invalid'
    if ($invalid.Code -ne 401) { throw ('MCP invalid JWT accepted: ' + $invalid.Code) }
    Write-Output 'MCP_INVALID_TOKEN_DENIED_OK'
} finally {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
    Write-Output 'ISOLATED_TEST_PROCESS_STOPPED'
}
