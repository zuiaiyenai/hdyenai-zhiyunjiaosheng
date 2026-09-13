param(
    [Parameter(Mandatory = $true)]
    [string]$Schema,
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,
    [Parameter(Mandatory = $true)]
    [int]$DurationSeconds,
    [Parameter(Mandatory = $true)]
    [int]$Backend1ProcessId,
    [Parameter(Mandatory = $true)]
    [int]$Backend2ProcessId,
    [int]$IntervalSeconds = 5,
    [int]$RedisDatabase = 14,
    [int]$RedisPort = 6380,
    [string[]]$ManagementUrls = @(
        "http://127.0.0.1:9091/actuator/prometheus",
        "http://127.0.0.1:9092/actuator/prometheus"
    ),
    [string]$MySqlExe = "D:\mysql-8.0.41-winx64\mysql-8.0.41-winx64\bin\mysql.exe",
    [string]$RedisCliExe = "D:\redis\redis-cli.exe"
)

$ErrorActionPreference = "Stop"

$baselineSchema = $Schema -match '^fctts_phase(11|13)_[a-z0-9_]+$'
$phase16Schema = $Schema -match '^fctts_phase16_[a-z0-9_]+$'
if ((-not $baselineSchema -and -not $phase16Schema) -or $Schema -eq 'zhiyunjiaos') {
    throw "Refusing unsafe schema name: $Schema"
}
if ($DurationSeconds -lt 1 -or $IntervalSeconds -lt 1) {
    throw "DurationSeconds and IntervalSeconds must be positive"
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$localConfig = Join-Path $projectRoot "config\application-local.yml"
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$samplesPath = Join-Path $OutputDirectory "resource-samples.jsonl"
$errorsPath = Join-Path $OutputDirectory "collector-errors.log"
$completePath = Join-Path $OutputDirectory "collector.complete"
$writer = [System.IO.StreamWriter]::new($samplesPath, $false, [System.Text.UTF8Encoding]::new($false))
$logicalProcessors = [Environment]::ProcessorCount
$backendProcessIds = @($Backend1ProcessId, $Backend2ProcessId)
$previousCpu = @{}
$previousAt = Get-Date
$phaseLabel = if ($Schema -match '^fctts_phase13_') { 'phase13' }
    elseif ($Schema -match '^fctts_phase16_') { 'phase16' }
    else { 'phase11' }

function Read-LocalScalar([string]$Pattern, [string]$Description) {
    $raw = Get-Content -Raw -LiteralPath $localConfig
    $match = [regex]::Match($raw, $Pattern)
    if (-not $match.Success) { throw "Missing $Description in ignored local config" }
    return $match.Groups[1].Value.Trim()
}

function Read-RedisPassword {
    if ($env:REDISCLI_AUTH) { return $env:REDISCLI_AUTH }
    if ($env:REDIS_PASSWORD) { return $env:REDIS_PASSWORD }
    throw "Dedicated load-test Redis password is missing from the process environment"
}

function Sum-PrometheusMetric([string[]]$Texts, [string]$Name, [string]$LabelPattern = '') {
    $sum = 0.0
    $matched = $false
    foreach ($text in $Texts) {
        foreach ($line in ($text -split "`n")) {
            if ($line -notmatch "^$([regex]::Escape($Name))(\{([^}]*)\})?\s+([-+0-9.eE]+)$") { continue }
            $labels = $Matches[2]
            $valueText = $Matches[3]
            if ($LabelPattern -and $labels -notmatch $LabelPattern) { continue }
            $sum += [double]::Parse($valueText, [Globalization.CultureInfo]::InvariantCulture)
            $matched = $true
        }
    }
    if ($matched) { return $sum }
    return $null
}

function Read-MySqlStatus([string]$Password) {
    $previousPassword = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $Password
        $query = "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Connections','Max_used_connections','Slow_queries','Aborted_connects'); SELECT '${phaseLabel}_users',COUNT(*) FROM ``$Schema``.user; SELECT '${phaseLabel}_tasks_pending',COUNT(*) FROM ``$Schema``.async_task WHERE status='PENDING';"
        $lines = & $MySqlExe -h 127.0.0.1 -P 3306 -u root --protocol=tcp --batch --skip-column-names -e $query
        if ($LASTEXITCODE -ne 0) { throw "mysql status query failed" }
        $result = @{}
        foreach ($line in $lines) {
            $parts = $line -split "`t", 2
            if ($parts.Count -eq 2) { $result[$parts[0]] = [double]$parts[1] }
        }
        return $result
    } finally {
        $env:MYSQL_PWD = $previousPassword
    }
}

function Read-RedisStatus([string]$Password) {
    $previousPassword = $env:REDISCLI_AUTH
    try {
        $env:REDISCLI_AUTH = $Password
        $clock = [Diagnostics.Stopwatch]::StartNew()
        $pong = & $RedisCliExe -h 127.0.0.1 -p $RedisPort -a $Password -n $RedisDatabase --raw PING
        $clock.Stop()
        if ($LASTEXITCODE -ne 0 -or $pong -ne 'PONG') { throw "redis ping failed" }
        $info = & $RedisCliExe -h 127.0.0.1 -p $RedisPort -a $Password -n $RedisDatabase --raw INFO
        $keys = & $RedisCliExe -h 127.0.0.1 -p $RedisPort -a $Password -n $RedisDatabase --raw DBSIZE
        $result = @{ ping_latency_ms = $clock.Elapsed.TotalMilliseconds; keys = [double]$keys }
        foreach ($line in $info) {
            if ($line -match '^(used_memory|connected_clients|blocked_clients|total_commands_processed|evicted_keys|keyspace_hits|keyspace_misses):([0-9]+)') {
                $result[$Matches[1]] = [double]$Matches[2]
            }
        }
        return $result
    } finally {
        $env:REDISCLI_AUTH = $previousPassword
    }
}

function Read-LoginStageMetrics([string[]]$Texts) {
    $result = @{}
    foreach ($stage in @('total', 'rate_limit', 'redis', 'database', 'bcrypt', 'jwt')) {
        $label = 'stage="' + $stage + '"'
        $result[$stage] = @{
            count = Sum-PrometheusMetric $Texts 'fctts_login_stage_seconds_count' $label
            sum_seconds = Sum-PrometheusMetric $Texts 'fctts_login_stage_seconds_sum' $label
            max_seconds = Sum-PrometheusMetric $Texts 'fctts_login_stage_seconds_max' $label
        }
    }
    return $result
}

try {
    $databasePassword = Read-LocalScalar '(?ms)^spring:\s*.*?datasource:\s*.*?password:\s*["'']?([^"''\r\n]*)' 'database password'
    $redisPassword = Read-RedisPassword
    $startedAt = Get-Date
    $sampleNumber = 0
    while (((Get-Date) - $startedAt).TotalSeconds -lt $DurationSeconds) {
        $sampleStarted = Get-Date
        $sampleNumber++
        $prometheusTexts = @()
        foreach ($url in $ManagementUrls) {
            try {
                $prometheusTexts += (Invoke-WebRequest -UseBasicParsing -TimeoutSec 4 -Uri $url).Content
            } catch {
                [System.IO.File]::AppendAllText($errorsPath,
                    "$([DateTime]::UtcNow.ToString('o')) prometheus $url $($_.Exception.Message)`n")
            }
        }
        if ($sampleNumber -eq 1 -and $prometheusTexts.Count -gt 0) {
            [System.IO.File]::WriteAllText((Join-Path $OutputDirectory 'prometheus-first.prom'),
                ($prometheusTexts -join "`n"), [System.Text.UTF8Encoding]::new($false))
        }

        $now = Get-Date
        $elapsed = [Math]::Max(0.001, ($now - $previousAt).TotalSeconds)
        $processes = @{}
        foreach ($processId in $backendProcessIds) {
            $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
            if (-not $process) {
                $processes["$processId"] = @{ alive = $false }
                continue
            }
            $cpuSeconds = $process.TotalProcessorTime.TotalSeconds
            $cpuPercent = $null
            if ($previousCpu.ContainsKey($processId)) {
                $cpuPercent = [Math]::Max(0, (($cpuSeconds - $previousCpu[$processId]) / $elapsed / $logicalProcessors) * 100)
            }
            $previousCpu[$processId] = $cpuSeconds
            $processes["$processId"] = @{
                alive = $true
                cpu_percent = $cpuPercent
                working_set_bytes = [double]$process.WorkingSet64
                private_bytes = [double]$process.PrivateMemorySize64
                handles = [double]$process.HandleCount
                threads = [double]$process.Threads.Count
            }
        }
        $previousAt = $now

        $hostCpu = ((Get-Counter '\Processor(_Total)\% Processor Time' -MaxSamples 1).CounterSamples |
            Select-Object -First 1 -ExpandProperty CookedValue)
        $availableMemoryMb = ((Get-Counter '\Memory\Available MBytes' -MaxSamples 1).CounterSamples |
            Select-Object -First 1 -ExpandProperty CookedValue)
        $mysql = Read-MySqlStatus $databasePassword
        $redis = Read-RedisStatus $redisPassword
        $prometheus = @{
            process_cpu_usage = Sum-PrometheusMetric $prometheusTexts 'process_cpu_usage'
            process_memory_rss_bytes = Sum-PrometheusMetric $prometheusTexts 'process_memory_rss_bytes'
            jvm_heap_used_bytes = Sum-PrometheusMetric $prometheusTexts 'jvm_memory_used_bytes' 'area="heap"'
            jvm_heap_committed_bytes = Sum-PrometheusMetric $prometheusTexts 'jvm_memory_committed_bytes' 'area="heap"'
            jvm_gc_pause_seconds_count = Sum-PrometheusMetric $prometheusTexts 'jvm_gc_pause_seconds_count'
            jvm_gc_pause_seconds_sum = Sum-PrometheusMetric $prometheusTexts 'jvm_gc_pause_seconds_sum'
            jvm_threads_live = Sum-PrometheusMetric $prometheusTexts 'jvm_threads_live_threads'
            tomcat_threads_busy = Sum-PrometheusMetric $prometheusTexts 'tomcat_threads_busy_threads'
            tomcat_threads_current = Sum-PrometheusMetric $prometheusTexts 'tomcat_threads_current_threads'
            tomcat_threads_max = Sum-PrometheusMetric $prometheusTexts 'tomcat_threads_config_max_threads'
            hikari_active = Sum-PrometheusMetric $prometheusTexts 'hikaricp_connections_active'
            hikari_idle = Sum-PrometheusMetric $prometheusTexts 'hikaricp_connections_idle'
            hikari_pending = Sum-PrometheusMetric $prometheusTexts 'hikaricp_connections_pending'
            hikari_max = Sum-PrometheusMetric $prometheusTexts 'hikaricp_connections_max'
            executor_active = Sum-PrometheusMetric $prometheusTexts 'executor_active_threads' 'name="fctts.async.tasks"'
            executor_queued = Sum-PrometheusMetric $prometheusTexts 'executor_queued_tasks' 'name="fctts.async.tasks"'
            executor_pool = Sum-PrometheusMetric $prometheusTexts 'executor_pool_size_threads' 'name="fctts.async.tasks"'
            executor_completed = Sum-PrometheusMetric $prometheusTexts 'executor_completed_tasks_total' 'name="fctts.async.tasks"'
            task_rejected = Sum-PrometheusMetric $prometheusTexts 'fctts_task_admission_rejected_total'
            bulkhead_active = Sum-PrometheusMetric $prometheusTexts 'fctts_resource_bulkhead_active'
            bulkhead_rejected = Sum-PrometheusMetric $prometheusTexts 'fctts_resource_bulkhead_rejected_total'
            login_stage = Read-LoginStageMetrics $prometheusTexts
        }
        $sample = [ordered]@{
            captured_at = [DateTime]::UtcNow.ToString('o')
            sample = $sampleNumber
            host = @{ cpu_percent = [double]$hostCpu; available_memory_mb = [double]$availableMemoryMb }
            processes = $processes
            prometheus = $prometheus
            mysql = $mysql
            redis = $redis
            scrape_count = $prometheusTexts.Count
        }
        $writer.WriteLine(($sample | ConvertTo-Json -Compress -Depth 8))
        $writer.Flush()
        $sleepMilliseconds = [Math]::Max(0, ($IntervalSeconds * 1000) - ((Get-Date) - $sampleStarted).TotalMilliseconds)
        if ($sleepMilliseconds -gt 0) { Start-Sleep -Milliseconds ([int]$sleepMilliseconds) }
    }
    if ($prometheusTexts.Count -gt 0) {
        [System.IO.File]::WriteAllText((Join-Path $OutputDirectory 'prometheus-last.prom'),
            ($prometheusTexts -join "`n"), [System.Text.UTF8Encoding]::new($false))
    }
    [System.IO.File]::WriteAllText($completePath, [DateTime]::UtcNow.ToString('o'),
        [System.Text.UTF8Encoding]::new($false))
} catch {
    [System.IO.File]::AppendAllText($errorsPath,
        "$([DateTime]::UtcNow.ToString('o')) fatal $($_.Exception.GetType().FullName) $($_.Exception.Message)`n")
    throw
} finally {
    $writer.Dispose()
}
