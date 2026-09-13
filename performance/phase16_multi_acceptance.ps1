param(
    [Parameter(Mandatory = $true)]
    [string]$Schema,
    [Parameter(Mandatory = $true)]
    [string]$UserPrefix,
    [Parameter(Mandatory = $true)]
    [string]$ResultsDirectory,
    [Parameter(Mandatory = $true)]
    [int]$Backend1ProcessId,
    [string]$Backend1Url = 'http://127.0.0.1:8081',
    [string]$Backend2Url = 'http://127.0.0.1:8082',
    [string]$GatewayUrl = 'http://127.0.0.1:8080',
    [string]$Backend2ReadinessUrl = 'http://127.0.0.1:9092/actuator/health/readiness',
    [string]$AudioFile = 'src\main\resources\static\audio-demos\male.mp3',
    [string]$MySqlExe = 'D:\mysql-8.0.41-winx64\mysql-8.0.41-winx64\bin\mysql.exe'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$rawPath = Join-Path $ResultsDirectory 'phase16-multi-functional-raw.json'
$result = [ordered]@{
    generated_at = [DateTime]::UtcNow.ToString('o')
    status = 'FAILED'
    checks = [ordered]@{}
}
$failure = $null

if ($Schema -notmatch '^fctts_phase16_[a-z0-9_]+$' -or $Schema -eq 'zhiyunjiaos') {
    throw "Refusing unsafe schema name: $Schema"
}
if (-not $env:LOAD_TEST_PASSWORD) { throw 'LOAD_TEST_PASSWORD is required' }
if (-not $env:MYSQL_PWD) { throw 'MYSQL_PWD is required' }
if (-not (Test-Path -LiteralPath $MySqlExe)) { throw "mysql.exe not found: $MySqlExe" }
$audioPath = (Resolve-Path -LiteralPath (Join-Path $projectRoot $AudioFile)).Path

function Invoke-MySql([string]$Sql) {
    $output = & $MySqlExe -h 127.0.0.1 -P 3306 -u root --protocol=tcp `
        --batch --skip-column-names -e $Sql
    if ($LASTEXITCODE -ne 0) { throw 'MySQL command failed during multi-instance acceptance' }
    return @($output)
}

function Invoke-MySqlScalar([string]$Sql) {
    return [string](@(Invoke-MySql $Sql)[-1])
}

function Escape-Sql([string]$Value) {
    return $Value.Replace("'", "''")
}

function Invoke-Json([string]$Method, [string]$Uri, [string]$Token, $Body = $null) {
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $arguments = @{
        Method = $Method
        Uri = $Uri
        Headers = $headers
        TimeoutSec = 20
    }
    if ($null -ne $Body) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = ($Body | ConvertTo-Json -Depth 5)
    }
    return Invoke-RestMethod @arguments
}

function New-AsrRequest([System.Net.Http.HttpClient]$Client, [string]$BaseUrl,
                        [string]$Token, [string]$Path) {
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post, "$BaseUrl/asr/transcribe")
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new(
        'Bearer', $Token)
    $multipart = [System.Net.Http.MultipartFormDataContent]::new()
    $stream = [System.IO.File]::OpenRead($Path)
    $fileContent = [System.Net.Http.StreamContent]::new($stream)
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new(
        'audio/mpeg')
    $multipart.Add($fileContent, 'file', [System.IO.Path]::GetFileName($Path))
    $multipart.Add([System.Net.Http.StringContent]::new('zh'), 'language')
    $request.Content = $multipart
    return [pscustomobject]@{
        Request = $request
        Multipart = $multipart
        Stream = $stream
        Task = $Client.SendAsync($request)
    }
}

function Wait-Task([string]$BaseUrl, [string]$Token, [string]$TaskId,
                   [int]$TimeoutSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $task = Invoke-Json 'Get' "$BaseUrl/api/tasks/$TaskId" $Token
        if ($task.status -in @('SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED')) { return $task }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "ASR task did not finish within ${TimeoutSeconds}s"
}

function Get-Bytes([string]$Uri, [string]$Token) {
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Get, $Uri)
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new(
            'Bearer', $Token)
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "Result download returned HTTP $([int]$response.StatusCode)"
        }
        return $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    } finally {
        if ($response) { $response.Dispose() }
        if ($request) { $request.Dispose() }
        $client.Dispose()
    }
}

function Get-Status([string]$Uri, [string]$Token) {
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Get, $Uri)
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new(
            'Bearer', $Token)
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        return [int]$response.StatusCode
    } finally {
        if ($response) { $response.Dispose() }
        if ($request) { $request.Dispose() }
        $client.Dispose()
    }
}

function Get-Sha256([byte[]]$Bytes) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '')
    } finally {
        $sha.Dispose()
    }
}

try {
    Add-Type -AssemblyName System.Net.Http
    New-Item -ItemType Directory -Force -Path $ResultsDirectory | Out-Null
    $username = "$UserPrefix$('{0:0000}' -f 1)"
    $login = Invoke-Json 'Post' "$Backend1Url/user/login" $null @{
        username = $username
        password = $env:LOAD_TEST_PASSWORD
    }
    if (-not $login.token) { throw 'backend-1 login did not return a JWT' }
    $token = [string]$login.token

    $voicePage = Invoke-Json 'Get' "$Backend2Url/voice_library/list?page=0&size=5" $token
    if ($null -eq $voicePage.content) { throw 'backend-2 rejected backend-1 JWT or returned an invalid voice page' }
    $result.checks.jwt_cross_instance = 'VERIFIED'

    Invoke-MySql @"
DROP TRIGGER IF EXISTS ``$Schema``.phase16_task_claim_trigger;
DROP TRIGGER IF EXISTS ``$Schema``.phase16_cleanup_claim_trigger;
DROP TABLE IF EXISTS ``$Schema``.phase16_task_claim_audit;
DROP TABLE IF EXISTS ``$Schema``.phase16_cleanup_claim_audit;
CREATE TABLE ``$Schema``.phase16_task_claim_audit (
  audit_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_id CHAR(36) NOT NULL,
  worker_id VARCHAR(128) NOT NULL,
  claimed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE ``$Schema``.phase16_cleanup_claim_audit (
  audit_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cleanup_id BIGINT UNSIGNED NOT NULL,
  claimed_by VARCHAR(64) NOT NULL,
  claimed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TRIGGER ``$Schema``.phase16_task_claim_trigger AFTER UPDATE ON ``$Schema``.async_task
FOR EACH ROW INSERT INTO ``$Schema``.phase16_task_claim_audit(task_id, worker_id)
SELECT NEW.task_id, NEW.worker_id
WHERE OLD.status <> 'RUNNING' AND NEW.status = 'RUNNING';
CREATE TRIGGER ``$Schema``.phase16_cleanup_claim_trigger AFTER UPDATE ON ``$Schema``.pending_file_cleanup
FOR EACH ROW INSERT INTO ``$Schema``.phase16_cleanup_claim_audit(cleanup_id, claimed_by)
SELECT NEW.cleanup_id, NEW.claimed_by
WHERE OLD.claimed_by IS NULL AND NEW.claimed_by IS NOT NULL;
"@ | Out-Null

    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    $request1 = New-AsrRequest $client $Backend1Url $token $audioPath
    $request2 = New-AsrRequest $client $Backend2Url $token $audioPath
    try {
        [System.Threading.Tasks.Task]::WaitAll(
            [System.Threading.Tasks.Task[]]@($request1.Task, $request2.Task))
        $response1 = $request1.Task.Result
        $response2 = $request2.Task.Result
        $body1 = $response1.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        $body2 = $response2.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        if ([int]$response1.StatusCode -ne 202 -or [int]$response2.StatusCode -ne 202) {
            throw 'Concurrent ASR submission did not return HTTP 202 from both backends'
        }
    } finally {
        if ($response1) { $response1.Dispose() }
        if ($response2) { $response2.Dispose() }
        $request1.Request.Dispose()
        $request2.Request.Dispose()
        $request1.Stream.Dispose()
        $request2.Stream.Dispose()
        $client.Dispose()
    }
    if ($body1.taskId -ne $body2.taskId) { throw 'Cross-instance deduplication returned different task IDs' }
    $duplicates = @([bool]$body1.duplicate, [bool]$body2.duplicate)
    if (@($duplicates | Where-Object { $_ }).Count -ne 1) {
        throw 'Concurrent ASR submission did not produce exactly one duplicate response'
    }
    $taskId = [string]$body1.taskId
    $result.task_id = $taskId
    $rowCount = [int](Invoke-MySqlScalar "SELECT COUNT(*) FROM ``$Schema``.async_task WHERE task_id = '$(Escape-Sql $taskId)';")
    if ($rowCount -ne 1) { throw 'Concurrent ASR submission created more than one database row' }
    $result.checks.cross_instance_deduplication = [ordered]@{
        status = 'VERIFIED'
        created_responses = 1
        duplicate_responses = 1
        database_rows = $rowCount
    }

    $winningBase = if (-not [bool]$body1.duplicate) { $Backend1Url } else { $Backend2Url }
    $otherBase = if ($winningBase -eq $Backend1Url) { $Backend2Url } else { $Backend1Url }
    $task = Wait-Task $otherBase $token $taskId
    if ($task.status -ne 'SUCCESS' -or [int]$task.attempts -ne 1 -or -not $task.resultData) {
        throw 'Real ASR task did not complete successfully in one attempt'
    }
    $taskAudit = (Invoke-MySqlScalar "SELECT COUNT(*), COUNT(DISTINCT worker_id) FROM ``$Schema``.phase16_task_claim_audit WHERE task_id = '$(Escape-Sql $taskId)';") -split "`t"
    if ([int]$taskAudit[0] -ne 1 -or [int]$taskAudit[1] -ne 1) {
        throw 'Two workers claimed the same ASR task'
    }
    $resultKey = [string]$task.resultData
    $download = Get-Bytes "$otherBase/api/tasks/$taskId/result" $token
    if ($download.Length -lt 2) { throw 'ASR result download was empty' }
    $result.checks.shared_task_execution = [ordered]@{
        status = 'VERIFIED'
        terminal_status = [string]$task.status
        attempts = [int]$task.attempts
        claim_events = [int]$taskAudit[0]
        distinct_workers = [int]$taskAudit[1]
        queried_from_other_backend = $true
        downloaded_bytes = $download.Length
        download_sha256 = Get-Sha256 $download
    }

    $escapedKey = Escape-Sql $resultKey
    $cleanupId = [long](Invoke-MySqlScalar "INSERT INTO ``$Schema``.pending_file_cleanup(storage_type, relative_path) VALUES ('VOICE_OBJECT','$escapedKey'); SELECT LAST_INSERT_ID();")
    $cleanupDeadline = (Get-Date).AddSeconds(30)
    do {
        $remaining = [int](Invoke-MySqlScalar "SELECT COUNT(*) FROM ``$Schema``.pending_file_cleanup WHERE cleanup_id = $cleanupId;")
        if ($remaining -eq 0) { break }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $cleanupDeadline)
    if ($remaining -ne 0) { throw 'Cleanup workers did not complete the OSS cleanup row' }
    $cleanupAudit = (Invoke-MySqlScalar "SELECT COUNT(*), COUNT(DISTINCT claimed_by) FROM ``$Schema``.phase16_cleanup_claim_audit WHERE cleanup_id = $cleanupId;") -split "`t"
    if ([int]$cleanupAudit[0] -ne 1 -or [int]$cleanupAudit[1] -ne 1) {
        throw 'Two cleanup workers claimed the same cleanup row'
    }
    $postCleanupStatus = Get-Status "$otherBase/api/tasks/$taskId/result" $token
    if ($postCleanupStatus -ge 200 -and $postCleanupStatus -lt 300) {
        throw 'OSS result remained readable after cleanup completed'
    }
    $result.checks.cleanup_claim = [ordered]@{
        status = 'VERIFIED'
        claim_events = [int]$cleanupAudit[0]
        distinct_workers = [int]$cleanupAudit[1]
        row_removed = $true
        result_read_after_cleanup_http_status = $postCleanupStatus
    }

    $backendProcess = Get-Process -Id $Backend1ProcessId -ErrorAction Stop
    Stop-Process -Id $backendProcess.Id -ErrorAction Stop
    $shutdownDeadline = (Get-Date).AddSeconds(20)
    while ((Get-Process -Id $Backend1ProcessId -ErrorAction SilentlyContinue) -and
            (Get-Date) -lt $shutdownDeadline) {
        Start-Sleep -Milliseconds 250
    }
    if (Get-Process -Id $Backend1ProcessId -ErrorAction SilentlyContinue) {
        throw 'backend-1 did not stop within 20 seconds'
    }
    $failoverStarted = Get-Date
    $failoverSuccess = 0
    for ($index = 0; $index -lt 20; $index++) {
        $page = Invoke-Json 'Get' "$GatewayUrl/voice_library/list?page=0&size=5" $token
        if ($null -ne $page.content) { $failoverSuccess++ }
        Start-Sleep -Milliseconds 50
    }
    if ($failoverSuccess -ne 20) { throw 'Nginx failover did not preserve all authenticated requests' }
    $readiness = Invoke-RestMethod -Method Get -Uri $Backend2ReadinessUrl -TimeoutSec 10
    if ($readiness.status -ne 'UP') { throw 'backend-2 readiness was not UP after backend-1 shutdown' }
    $result.checks.backend_failover = [ordered]@{
        status = 'VERIFIED'
        old_token_requests = 20
        successful_requests = $failoverSuccess
        elapsed_ms = [Math]::Round(((Get-Date) - $failoverStarted).TotalMilliseconds, 3)
        backend_2_readiness = [string]$readiness.status
    }
    $result.status = 'VERIFIED'
} catch {
    $failure = $_
    $result.failure = $_.Exception.Message
} finally {
    [System.IO.File]::WriteAllText($rawPath, ($result | ConvertTo-Json -Depth 10),
        [System.Text.UTF8Encoding]::new($false))
}

if ($failure) { throw $failure }
Write-Output "PHASE16_MULTI_FUNCTIONAL_RAW=$rawPath"
