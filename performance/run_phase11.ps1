param(
    [int[]]$UserLevels = @(10, 50, 100, 200),
    [int]$Repetitions = 3,
    [string]$WarmupDuration = "2m",
    [string]$SteadyDuration = "10m",
    [int]$StabilizationSeconds = 300,
    [int]$CollectorTailSeconds = 90,
    [int]$RedisDatabase = 14,
    [int]$RedisPort = 6380,
    [string]$K6Exe = "target\tools\k6-v2.2.0-windows-amd64\k6.exe",
    [string]$MySqlExe = "D:\mysql-8.0.41-winx64\mysql-8.0.41-winx64\bin\mysql.exe",
    [string]$RedisCliExe = "D:\redis\redis-cli.exe",
    [string]$RedisServerExe = "D:\redis\redis-server.exe"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot
$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddHHmmss')
$runId = "p11_$timestamp"
$schema = "fctts_phase11_$timestamp"
$userPrefix = "phase11_${runId}_"
$resultsRoot = Join-Path $projectRoot "target\phase11-live-$timestamp"
$manifestPath = Join-Path $resultsRoot "run-manifest.json"
$localConfig = Join-Path $projectRoot "config\application-local.yml"
$jar = Join-Path $projectRoot "target\tts-0.0.1-SNAPSHOT.jar"
$backendProcesses = @()
$redisProcess = $null
$nginxStarted = $false
$priorLoadPassword = $env:LOAD_TEST_PASSWORD
$priorRedisAuth = $env:REDISCLI_AUTH
$priorRedisPassword = $env:REDIS_PASSWORD
$priorMySqlPassword = $env:MYSQL_PWD
$priorDbUrl = $env:DB_URL
$priorSpringDatasourceUrl = $env:SPRING_DATASOURCE_URL

if ($UserLevels.Count -eq 0 -or ($UserLevels | Where-Object { $_ -notin @(10, 50, 100, 200) })) {
    throw "UserLevels must contain only 10, 50, 100, and 200"
}
if ($Repetitions -lt 1) { throw "Repetitions must be positive" }
if ($CollectorTailSeconds -lt 10) { throw "CollectorTailSeconds must be at least 10" }
if ($RedisDatabase -lt 1) { throw "Use an isolated non-default Redis database" }
if (-not (Test-Path -LiteralPath $localConfig)) { throw "Ignored config/application-local.yml is required" }
if (-not (Test-Path -LiteralPath $K6Exe)) { throw "Portable k6 not found: $K6Exe" }
if (-not (Test-Path -LiteralPath $MySqlExe)) { throw "mysql.exe not found: $MySqlExe" }
if (-not (Test-Path -LiteralPath $RedisCliExe)) { throw "redis-cli.exe not found: $RedisCliExe" }
if (-not (Test-Path -LiteralPath $RedisServerExe)) { throw "redis-server.exe not found: $RedisServerExe" }

New-Item -ItemType Directory -Force -Path $resultsRoot | Out-Null

function Read-LocalScalar([string]$Pattern, [string]$Description) {
    $raw = Get-Content -Raw -LiteralPath $localConfig
    $match = [regex]::Match($raw, $Pattern)
    if (-not $match.Success) { throw "Missing $Description in ignored local config" }
    return $match.Groups[1].Value.Trim()
}

function Read-RedisPassword {
    return "P11Redis-" + [Guid]::NewGuid().ToString('N')
}

function Convert-DurationToSeconds([string]$Duration) {
    if ($Duration -notmatch '^(\d+)(s|m|h)$') { throw "Unsupported duration: $Duration" }
    $value = [int]$Matches[1]
    switch ($Matches[2]) { 's' { $value } 'm' { $value * 60 } 'h' { $value * 3600 } }
}

function Invoke-MySql([string]$Sql) {
    & $MySqlExe -h 127.0.0.1 -P 3306 -u root --protocol=tcp --batch --skip-column-names -e $Sql
    if ($LASTEXITCODE -ne 0) { throw "MySQL command failed" }
}

function Wait-Ready([string]$Url, [int]$TimeoutSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri $Url
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Backend did not become ready: $Url"
}

function Test-TcpPort([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        return $task.Wait(500) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-Redis([int]$TimeoutSeconds = 30) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $pong = & $RedisCliExe -h 127.0.0.1 -p $RedisPort -a $redisPassword -n $RedisDatabase --raw PING 2>$null
        if ($LASTEXITCODE -eq 0 -and $pong -eq 'PONG') { return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Dedicated Phase 11 Redis did not become ready on port $RedisPort"
}

function Start-Backend([int]$ServerPort, [int]$ManagementPort, [string]$Name) {
    $stdout = Join-Path $resultsRoot "$Name.stdout.log"
    $stderr = Join-Path $resultsRoot "$Name.stderr.log"
    $arguments = @(
        '-Xms256m', '-Xmx512m', '-jar', $jar,
        '--spring.profiles.active=local',
        "--spring.config.additional-location=file:///$($localConfig.Replace('\', '/'))",
        '--spring.data.redis.host=127.0.0.1',
        "--spring.data.redis.port=$RedisPort",
        "--spring.data.redis.database=$RedisDatabase",
        "--server.port=$ServerPort",
        "--management.server.port=$ManagementPort",
        '--server.tomcat.mbeanregistry.enabled=true',
        '--app.storage.provider=aliyun-oss',
        '--app.tasks.worker-count=1',
        '--app.tasks.poll-interval=24h',
        '--app.tasks.global-queue-limit=200',
        '--app.tasks.per-user-concurrency=2',
        '--app.observability.external-services-required=false',
        "--app.observability.environment=phase11-$Name"
    )
    $process = Start-Process -FilePath 'java.exe' -ArgumentList $arguments -PassThru `
        -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    return $process
}

function Assert-BackendSchema([string]$Name) {
    $stdout = Join-Path $resultsRoot "$Name.stdout.log"
    $expected = "jdbc:mysql://127.0.0.1:3306/$schema"
    $content = Get-Content -Raw -LiteralPath $stdout
    if (-not $content.Contains($expected)) {
        throw "$Name did not connect to the isolated Phase 11 schema"
    }
}

function Reset-GeneratedTasks {
    Invoke-MySql "DELETE FROM ``$schema``.async_task WHERE owner_username LIKE '$userPrefix%' AND status <> 'SUCCESS';"
}

$databasePassword = Read-LocalScalar '(?ms)^spring:\s*.*?datasource:\s*.*?password:\s*["'']?([^"''\r\n]*)' 'database password'
$redisPassword = Read-RedisPassword
$env:MYSQL_PWD = $databasePassword
$env:REDISCLI_AUTH = $redisPassword
$env:REDIS_PASSWORD = $redisPassword
$env:LOAD_TEST_PASSWORD = "P11-" + [Guid]::NewGuid().ToString('N')
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/${schema}?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
$env:SPRING_DATASOURCE_URL = $env:DB_URL
$redisConfigPath = Join-Path $resultsRoot 'redis-phase11.conf'

try {
    foreach ($port in @(8080, 8081, 8082, 9091, 9092, $RedisPort)) {
        if (Test-TcpPort $port) {
            throw "Port $port is already in use"
        }
    }
    $redisConfig = @(
        'bind 127.0.0.1',
        'protected-mode yes',
        "port $RedisPort",
        'save ""',
        'appendonly no',
        'daemonize no',
        'logfile ""',
        'databases 16',
        "requirepass $redisPassword"
    ) -join "`r`n"
    [System.IO.File]::WriteAllText($redisConfigPath, $redisConfig, [System.Text.UTF8Encoding]::new($false))
    $redisProcess = Start-Process -FilePath $RedisServerExe -ArgumentList $redisConfigPath -PassThru `
        -WindowStyle Hidden -RedirectStandardOutput (Join-Path $resultsRoot 'redis.stdout.log') `
        -RedirectStandardError (Join-Path $resultsRoot 'redis.stderr.log')
    Wait-Redis
    Invoke-MySql "CREATE DATABASE ``$schema`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    & $RedisCliExe -h 127.0.0.1 -p $RedisPort -a $redisPassword -n $RedisDatabase --raw FLUSHDB | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to clear isolated Redis DB $RedisDatabase" }

    $testTemp = Join-Path $projectRoot 'target\test-tmp'
    New-Item -ItemType Directory -Force -Path $testTemp | Out-Null
    & mvn "-Djava.io.tmpdir=$testTemp" -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Maven package failed" }

    $backend1 = Start-Backend 8081 9091 'backend-1'
    $backendProcesses += $backend1
    Wait-Ready 'http://127.0.0.1:9091/actuator/health/readiness'
    Assert-BackendSchema 'backend-1'
    & (Join-Path $PSScriptRoot 'prepare_data.ps1') -Schema $schema -RunId $runId -MySqlExe $MySqlExe
    if ($LASTEXITCODE -ne 0) { throw "Phase 11 data preparation failed" }
    $backend2 = Start-Backend 8082 9092 'backend-2'
    $backendProcesses += $backend2
    Wait-Ready 'http://127.0.0.1:9092/actuator/health/readiness'
    Assert-BackendSchema 'backend-2'

    & cmd.exe /c start-frontend-nginx.bat
    if ($LASTEXITCODE -ne 0) { throw "Nginx startup failed" }
    $nginxStarted = $true
    Wait-Ready 'http://127.0.0.1:8080/'

    $manifest = [ordered]@{
        run_id = $runId
        schema = $schema
        redis_database = $RedisDatabase
        redis_port = $RedisPort
        redis_isolation = 'dedicated ephemeral process; persistence disabled'
        git_sha = (git rev-parse HEAD).Trim()
        started_at = [DateTime]::UtcNow.ToString('o')
        k6_version = (& $K6Exe version | Out-String).Trim()
        java_version = (& cmd.exe /d /c 'java -version 2>&1' | Out-String).Trim()
        mysql_version = (Invoke-MySql 'SELECT VERSION();' | Out-String).Trim()
        user_levels = $UserLevels
        repetitions = $Repetitions
        warmup_duration = $WarmupDuration
        steady_duration = $SteadyDuration
        stabilization_seconds = $StabilizationSeconds
        collector_tail_seconds = $CollectorTailSeconds
        topology = 'nginx -> backend-1/backend-2 -> shared MySQL/Redis'
        lane = 'L0_CORE_API'
        media_execution = 'excluded; DB workers poll every 24h during Phase 11'
        data_baseline = @{ registered_users = 1000; active_users = 200; projects_per_active = 10; historical_tasks_per_active = 50; public_voices = 200 }
        runs = @()
    }
    [System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))

    if ($StabilizationSeconds -gt 0) { Start-Sleep -Seconds $StabilizationSeconds }
    $collectorDuration = (Convert-DurationToSeconds $WarmupDuration) +
        (Convert-DurationToSeconds $SteadyDuration) + $CollectorTailSeconds

    foreach ($users in $UserLevels) {
        for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
            Reset-GeneratedTasks
            $caseId = "${users}u-r$repetition"
            $caseDirectory = Join-Path $resultsRoot $caseId
            New-Item -ItemType Directory -Force -Path $caseDirectory | Out-Null
            $summaryPath = Join-Path $caseDirectory 'k6-summary.json'
            $rawPath = Join-Path $caseDirectory 'k6-raw.json'
            $collectorArgs = @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'collect_metrics.ps1'),
                '-Schema', $schema, '-OutputDirectory', $caseDirectory,
                '-DurationSeconds', $collectorDuration,
                '-Backend1ProcessId', $backend1.Id,
                '-Backend2ProcessId', $backend2.Id,
                '-RedisDatabase', $RedisDatabase,
                '-RedisPort', $RedisPort
            )
            $collectorStdout = Join-Path $caseDirectory 'collector.stdout.log'
            $collectorStderr = Join-Path $caseDirectory 'collector.stderr.log'
            $collector = Start-Process -FilePath 'powershell.exe' -ArgumentList $collectorArgs `
                -PassThru -WindowStyle Hidden -RedirectStandardOutput $collectorStdout `
                -RedirectStandardError $collectorStderr

            $env:BASE_URL = 'http://127.0.0.1:8080'
            $env:VUS = "$users"
            $env:ACCOUNT_COUNT = '1000'
            $env:WARMUP_DURATION = $WarmupDuration
            $env:STEADY_DURATION = $SteadyDuration
            $env:RUN_ID = "$runId-$caseId"
            $env:SEED = "$runId-$caseId"
            $env:USER_PREFIX = $userPrefix
            $env:SUMMARY_PATH = $summaryPath
            & $K6Exe run --quiet --out "json=$rawPath" (Join-Path $PSScriptRoot 'core-api.js')
            $k6ExitCode = $LASTEXITCODE
            $collectorCompleted = $collector.WaitForExit(($collectorDuration + 30) * 1000)
            if (-not $collectorCompleted) {
                Stop-Process -Id $collector.Id -Force
                $collector.WaitForExit()
            }
            $resourceSamplesPath = Join-Path $caseDirectory 'resource-samples.jsonl'
            $collectorCompletePath = Join-Path $caseDirectory 'collector.complete'
            $collectorComplete = Test-Path -LiteralPath $collectorCompletePath

            $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
            $manifest.runs += [pscustomobject]@{
                case_id = $caseId
                users = $users
                repetition = $repetition
                k6_exit_code = $k6ExitCode
                collector_complete = $collectorComplete
                completed_at = [DateTime]::UtcNow.ToString('o')
                summary = "$caseId/k6-summary.json"
                resources = "$caseId/resource-samples.jsonl"
                raw = "$caseId/k6-raw.json"
            }
            [System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
            if (-not $collectorComplete -or -not (Test-Path -LiteralPath $resourceSamplesPath) -or
                    (Get-Item -LiteralPath $resourceSamplesPath).Length -eq 0) {
                throw "Resource collector failed for $caseId; inspect collector.stderr.log and collector-errors.log"
            }
            Start-Sleep -Seconds 30
        }
    }
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $manifest | Add-Member -NotePropertyName completed_at -NotePropertyValue ([DateTime]::UtcNow.ToString('o')) -Force
    [System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
    Write-Output "PHASE11_RESULTS=$resultsRoot"
} finally {
    if ($nginxStarted) { & cmd.exe /c stop-frontend-nginx.bat | Out-Null }
    foreach ($process in $backendProcesses) {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep -Seconds 2
    if ($schema -match '^fctts_phase11_[a-z0-9_]+$' -and $schema -ne 'zhiyunjiaos') {
        try { Invoke-MySql "DROP DATABASE IF EXISTS ``$schema``;" } catch { Write-Warning $_ }
    }
    try { & $RedisCliExe -h 127.0.0.1 -p $RedisPort -a $redisPassword -n $RedisDatabase --raw FLUSHDB | Out-Null } catch { Write-Warning $_ }
    if ($redisProcess -and -not $redisProcess.HasExited) {
        Stop-Process -Id $redisProcess.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $redisConfigPath -Force -ErrorAction SilentlyContinue
    $env:LOAD_TEST_PASSWORD = $priorLoadPassword
    $env:REDISCLI_AUTH = $priorRedisAuth
    $env:REDIS_PASSWORD = $priorRedisPassword
    $env:MYSQL_PWD = $priorMySqlPassword
    $env:DB_URL = $priorDbUrl
    $env:SPRING_DATASOURCE_URL = $priorSpringDatasourceUrl
}
