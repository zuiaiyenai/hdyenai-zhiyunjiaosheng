param(
    [ValidateSet("phase11", "phase13", "phase16-login", "phase16-multi", "phase16-soak")]
    [string]$RunPhase = "phase11",
    [int[]]$UserLevels = @(10, 50, 100, 200),
    [int]$Repetitions = 3,
    [string]$WarmupDuration = "2m",
    [string]$SteadyDuration = "10m",
    [int]$StabilizationSeconds = 300,
    [int]$CollectorTailSeconds = 90,
    [ValidateRange(0, 100)]
    [int]$TaskSharePercent = 20,
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
$phaseNumber = if ($RunPhase -eq "phase13") { 13 }
    elseif ($RunPhase -in @("phase16-login", "phase16-multi", "phase16-soak")) { 16 }
    else { 11 }
$runId = "p${phaseNumber}_$timestamp"
$schema = "fctts_phase${phaseNumber}_$timestamp"
$userPrefix = "phase${phaseNumber}_${runId}_"
$resultsRoot = Join-Path $projectRoot "target\$RunPhase-live-$timestamp"
$manifestPath = Join-Path $resultsRoot "run-manifest.json"
$localConfig = Join-Path $projectRoot "config\application-local.yml"
$jar = Join-Path $projectRoot "target\tts-0.0.1-SNAPSHOT.jar"
$javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { $null }
$backendProcesses = @()
$redisProcess = $null
$nginxStarted = $false
$priorLoadPassword = $env:LOAD_TEST_PASSWORD
$priorRedisAuth = $env:REDISCLI_AUTH
$priorRedisPassword = $env:REDIS_PASSWORD
$priorMySqlPassword = $env:MYSQL_PWD
$priorDbUrl = $env:DB_URL
$priorSpringDatasourceUrl = $env:SPRING_DATASOURCE_URL
$priorTtsIntervalSeconds = $env:TTS_INTERVAL_SECONDS
$nginxAccessLog = Join-Path $projectRoot 'deploy\nginx\logs\access.log'
$nginxAccessLogStartLine = if (Test-Path -LiteralPath $nginxAccessLog) {
    @(Get-Content -LiteralPath $nginxAccessLog).Count
} else { 0 }

if (($RunPhase -eq "phase11" -or $RunPhase -eq "phase16-login") -and
        ($UserLevels.Count -eq 0 -or ($UserLevels | Where-Object { $_ -notin @(10, 50, 100, 200) }))) {
    throw "$RunPhase UserLevels must contain only 10, 50, 100, and 200"
}
if ($RunPhase -eq "phase16-multi" -and
        ($UserLevels.Count -ne 2 -or $UserLevels[0] -ne 100 -or $UserLevels[1] -ne 200 -or
         $Repetitions -ne 1 -or $WarmupDuration -ne "10s" -or $SteadyDuration -ne "60s")) {
    throw "Phase 16 multi-instance requires 100/200 VUs, one repetition, 10s warmup, and 60s steady duration"
}
if ($RunPhase -eq "phase13" -and
        ($UserLevels.Count -ne 1 -or $UserLevels[0] -ne 150 -or $Repetitions -ne 1 -or
         $WarmupDuration -ne "2m" -or $SteadyDuration -notin @("30m", "60m"))) {
    throw "Phase 13 requires 150 VUs, one repetition, 2m warmup, and 30m or 60m steady duration"
}
if ($RunPhase -eq "phase16-soak" -and
        ($UserLevels.Count -ne 1 -or $UserLevels[0] -ne 100 -or $Repetitions -ne 1 -or
         $WarmupDuration -ne "2m" -or $SteadyDuration -notin @("30m", "60m"))) {
    throw "Phase 16 soak requires 100 VUs, one repetition, 2m warmup, and 30m or 60m steady duration"
}
if ($Repetitions -lt 1) { throw "Repetitions must be positive" }
if ($CollectorTailSeconds -lt 10) { throw "CollectorTailSeconds must be at least 10" }
if ($RedisDatabase -lt 1) { throw "Use an isolated non-default Redis database" }
if (-not (Test-Path -LiteralPath $localConfig)) { throw "Ignored config/application-local.yml is required" }
if (-not (Test-Path -LiteralPath $K6Exe)) { throw "Portable k6 not found: $K6Exe" }
if (-not (Test-Path -LiteralPath $MySqlExe)) { throw "mysql.exe not found: $MySqlExe" }
if (-not (Test-Path -LiteralPath $RedisCliExe)) { throw "redis-cli.exe not found: $RedisCliExe" }
if (-not (Test-Path -LiteralPath $RedisServerExe)) { throw "redis-server.exe not found: $RedisServerExe" }
if (-not $javaExe -or -not (Test-Path -LiteralPath $javaExe)) {
    throw "JAVA_HOME must point to the project Java runtime"
}

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

function Get-ListeningProcessId([int]$Port) {
    $connection = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $connection) { throw "No listening process found on port $Port" }
    return [int]$connection.OwningProcess
}

function Wait-Redis([int]$TimeoutSeconds = 30) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $pong = & $RedisCliExe -h 127.0.0.1 -p $RedisPort -a $redisPassword -n $RedisDatabase --raw PING 2>$null
        if ($LASTEXITCODE -eq 0 -and $pong -eq 'PONG') { return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Dedicated $RunPhase Redis did not become ready on port $RedisPort"
}

function Start-Backend([int]$ServerPort, [int]$ManagementPort, [string]$Name,
                       [bool]$ActiveWorkers = $false) {
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
        "--management.metrics.tags.environment=$RunPhase-$Name",
        '--server.tomcat.mbeanregistry.enabled=true',
        '--app.storage.provider=aliyun-oss',
        '--app.tasks.worker-count=1',
        $(if ($ActiveWorkers) { '--app.tasks.poll-interval=250ms' }
          else { '--app.tasks.poll-interval=24h' }),
        '--app.tasks.global-queue-limit=200',
        '--app.tasks.per-user-concurrency=2',
        $(if ($RunPhase -in @('phase16-multi', 'phase16-soak')) { '--app.observability.external-services-required=true' }
          else { '--app.observability.external-services-required=false' }),
        $(if ($ActiveWorkers) { '--app.cleanup.retry-delay=500ms' }
          else { '--app.cleanup.retry-delay=5m' }),
        '--app.cleanup.claim-timeout=5s',
        "--app.observability.environment=$RunPhase-$Name"
    )
    if ($RunPhase -eq 'phase16-soak') { $arguments += '--tts.api.timeout=30s' }
    $process = Start-Process -FilePath $javaExe -ArgumentList $arguments -PassThru `
        -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    return $process
}

function Add-NginxUpstreamEvidence($Manifest) {
    $counts = [ordered]@{ backend_1 = 0; backend_2 = 0; other = 0 }
    if (Test-Path -LiteralPath $nginxAccessLog) {
        @(Get-Content -LiteralPath $nginxAccessLog | Select-Object -Skip $nginxAccessLogStartLine) |
            ForEach-Object {
                if ($_ -match 'upstream=127\.0\.0\.1:8081') { $counts.backend_1++ }
                elseif ($_ -match 'upstream=127\.0\.0\.1:8082') { $counts.backend_2++ }
                elseif ($_ -match 'upstream=([^ ]+)') { $counts.other++ }
            }
    }
    $Manifest | Add-Member -NotePropertyName nginx_upstreams -NotePropertyValue ([pscustomobject]$counts) -Force
}

function Assert-BackendSchema([string]$Name) {
    $stdout = Join-Path $resultsRoot "$Name.stdout.log"
    $expected = "jdbc:mysql://127.0.0.1:3306/$schema"
    $content = Get-Content -Raw -LiteralPath $stdout
    if (-not $content.Contains($expected)) {
        throw "$Name did not connect to the isolated $RunPhase schema"
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
$env:LOAD_TEST_PASSWORD = "P${phaseNumber}-" + [Guid]::NewGuid().ToString('N')
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/${schema}?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
$env:SPRING_DATASOURCE_URL = $env:DB_URL
$redisConfigPath = Join-Path $resultsRoot "redis-$RunPhase.conf"

try {
    foreach ($port in @(8080, 8081, 8082, 9091, 9092, $RedisPort)) {
        if (Test-TcpPort $port) {
            throw "Port $port is already in use"
        }
    }
    $modelProcessIds = ''
    if ($RunPhase -eq 'phase16-soak') {
        if (-not (Test-TcpPort 9880) -or -not (Test-TcpPort 9977)) {
            throw 'Phase 16 soak requires real GPT-SoVITS on 9880 and FunASR on 9977'
        }
        $modelProcessIds = "$(Get-ListeningProcessId 9880),$(Get-ListeningProcessId 9977)"
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
    if ($LASTEXITCODE -ne 0) { throw "$RunPhase data preparation failed" }
    $backend2 = Start-Backend 8082 9092 'backend-2'
    $backendProcesses += $backend2
    Wait-Ready 'http://127.0.0.1:9092/actuator/health/readiness'
    Assert-BackendSchema 'backend-2'

    & cmd.exe /c start-frontend-nginx.bat
    if ($LASTEXITCODE -ne 0) { throw "Nginx startup failed" }
    $nginxStarted = $true
    Wait-Ready 'http://127.0.0.1:8080/'

    $manifest = [ordered]@{
        phase = $phaseNumber
        run_id = $runId
        schema = $schema
        redis_database = $RedisDatabase
        redis_port = $RedisPort
        redis_isolation = 'dedicated ephemeral process; persistence disabled'
        git_sha = (git rev-parse HEAD).Trim()
        started_at = [DateTime]::UtcNow.ToString('o')
        k6_version = (& $K6Exe version | Out-String).Trim()
        java_executable = $javaExe
        java_version = (& cmd.exe /d /s /c "`"$javaExe`" -version 2>&1" | Out-String).Trim()
        mysql_version = (Invoke-MySql 'SELECT VERSION();' | Out-String).Trim()
        user_levels = $UserLevels
        repetitions = $Repetitions
        warmup_duration = $WarmupDuration
        steady_duration = $SteadyDuration
        stabilization_seconds = $StabilizationSeconds
        collector_tail_seconds = $CollectorTailSeconds
        task_share_percent = $TaskSharePercent
        topology = 'nginx -> backend-1/backend-2 -> shared MySQL/Redis'
        lane = if ($RunPhase -eq 'phase16-login') { 'LOGIN_PROFILE' }
            elseif ($RunPhase -eq 'phase16-multi') { 'MULTI_INSTANCE_MIXED' }
            elseif ($RunPhase -eq 'phase16-soak') { 'SOAK_MIXED_WITH_REAL_TTS' }
            else { 'L0_CORE_API' }
        media_execution = if ($RunPhase -eq 'phase16-login') {
            'excluded; sustained login-only profiling with DB workers polling every 24h'
        } elseif ($RunPhase -eq 'phase16-multi') {
            'load lane enqueues/polls a small task share with workers suspended; functional lane restarts two 250ms workers for one real ASR task'
        } elseif ($RunPhase -eq 'phase16-soak') {
            'workers suspended; one designated VU calls real GPT-SoVITS every 300 seconds'
        } else {
            "excluded; DB workers poll every 24h during $RunPhase L0 mixed workload"
        }
        data_baseline = @{ registered_users = 1000; active_users = 200; projects_per_active = 10; historical_tasks_per_active = 50; public_voices = 200 }
        model_process_ids = $modelProcessIds
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
            if ($modelProcessIds) { $collectorArgs += @('-ModelProcessIds', $modelProcessIds) }
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
            $env:TASK_SHARE_PERCENT = "$TaskSharePercent"
            $env:TTS_INTERVAL_SECONDS = if ($RunPhase -eq 'phase16-soak') { '300' } else { '0' }
            $k6Script = if ($RunPhase -eq 'phase16-login') { 'login-profile.js' } else { 'core-api.js' }
            & $K6Exe run --quiet --out "json=$rawPath" (Join-Path $PSScriptRoot $k6Script)
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
    if ($RunPhase -eq 'phase16-multi') {
        foreach ($process in @($backend1, $backend2)) {
            if ($process -and -not $process.HasExited) {
                Stop-Process -Id $process.Id -ErrorAction Stop
                $process.WaitForExit()
            }
        }
        Reset-GeneratedTasks
        $backend1 = Start-Backend 8081 9091 'backend-1-functional' $true
        $backendProcesses += $backend1
        Wait-Ready 'http://127.0.0.1:9091/actuator/health/readiness'
        Assert-BackendSchema 'backend-1-functional'
        $backend2 = Start-Backend 8082 9092 'backend-2-functional' $true
        $backendProcesses += $backend2
        Wait-Ready 'http://127.0.0.1:9092/actuator/health/readiness'
        Assert-BackendSchema 'backend-2-functional'
        $functionalScript = Join-Path $PSScriptRoot 'phase16_multi_acceptance.ps1'
        & $functionalScript -Schema $schema -UserPrefix $userPrefix -ResultsDirectory $resultsRoot `
            -Backend1ProcessId $backend1.Id -MySqlExe $MySqlExe
        if ($LASTEXITCODE -ne 0) { throw 'Phase 16 multi-instance functional acceptance failed' }

        $functionalRawPath = Join-Path $resultsRoot 'phase16-multi-functional-raw.json'
        $functional = Get-Content -Raw -LiteralPath $functionalRawPath | ConvertFrom-Json
        $backend1 = Start-Backend 8081 9091 'backend-1-restarted' $true
        $backendProcesses += $backend1
        Wait-Ready 'http://127.0.0.1:9091/actuator/health/readiness'
        Assert-BackendSchema 'backend-1-restarted'
        $loginBody = @{ username = "$userPrefix$('{0:0000}' -f 1)"; password = $env:LOAD_TEST_PASSWORD } | ConvertTo-Json
        $login = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/user/login' `
            -ContentType 'application/json' -Body $loginBody -TimeoutSec 15
        $headers = @{ Authorization = "Bearer $($login.token)" }
        $taskAfterRestart = Invoke-RestMethod -Method Get `
            -Uri "http://127.0.0.1:8081/api/tasks/$($functional.task_id)" `
            -Headers $headers -TimeoutSec 15
        if ($taskAfterRestart.status -ne 'SUCCESS' -or [int]$taskAfterRestart.attempts -ne 1) {
            throw 'Task state did not survive backend restart'
        }
        $functional | Add-Member -NotePropertyName restart_check -NotePropertyValue ([pscustomobject]@{
            backend = 'backend-1-restarted'
            readiness = 'UP'
            task_status = [string]$taskAfterRestart.status
            attempts = [int]$taskAfterRestart.attempts
        }) -Force
        [System.IO.File]::WriteAllText($functionalRawPath,
            ($functional | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
    }
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    Add-NginxUpstreamEvidence $manifest
    $manifest | Add-Member -NotePropertyName completed_at -NotePropertyValue ([DateTime]::UtcNow.ToString('o')) -Force
    [System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
    Write-Output "PHASE${phaseNumber}_RESULTS=$resultsRoot"
} finally {
    if ($nginxStarted) { & cmd.exe /c stop-frontend-nginx.bat | Out-Null }
    foreach ($process in $backendProcesses) {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep -Seconds 2
    $baselineSchema = $schema -match '^fctts_phase(11|13)_[a-z0-9_]+$'
    $phase16Schema = $schema -match '^fctts_phase16_[a-z0-9_]+$'
    if (($baselineSchema -or $phase16Schema) -and $schema -ne 'zhiyunjiaos') {
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
    $env:TTS_INTERVAL_SECONDS = $priorTtsIntervalSeconds
}
