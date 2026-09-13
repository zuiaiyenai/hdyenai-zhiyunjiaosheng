param(
    [Parameter(Mandatory = $true)]
    [string]$ResultsDirectory
)

$ErrorActionPreference = "Stop"
$manifestPath = Join-Path $ResultsDirectory 'run-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath)) { throw "Missing run manifest: $manifestPath" }
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$phaseNumber = if ($manifest.PSObject.Properties['phase']) { [int]$manifest.phase } else { 11 }
if ($phaseNumber -notin @(11, 13, 16)) { throw "Unsupported performance phase: $phaseNumber" }
if ($phaseNumber -eq 16 -and $manifest.lane -ne 'MULTI_INSTANCE_MIXED') {
    throw "Phase 16 summary currently supports only the multi-instance mixed lane"
}
$phaseLabel = "phase$phaseNumber"
$pendingTasksMetric = "${phaseLabel}_tasks_pending"

function Metric($Summary, [string]$Name) {
    $property = $Summary.metrics.PSObject.Properties[$Name]
    if ($property) { return $property.Value }
    return $null
}

function Value($Metric, [string]$Name) {
    if (-not $Metric) { return $null }
    $property = $Metric.values.PSObject.Properties[$Name]
    if ($property) { return [double]$property.Value }
    return $null
}

function Percentile([double[]]$Values, [double]$Quantile) {
    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $ordered = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($ordered.Count * $Quantile) - 1)
    return [double]$ordered[$index]
}

function Max-Number($Samples, [scriptblock]$Selector) {
    $values = @($Samples | ForEach-Object $Selector | Where-Object { $null -ne $_ })
    if ($values.Count -eq 0) { return $null }
    return [double](($values | Measure-Object -Maximum).Maximum)
}

function Min-Number($Samples, [scriptblock]$Selector) {
    $values = @($Samples | ForEach-Object $Selector | Where-Object { $null -ne $_ })
    if ($values.Count -eq 0) { return $null }
    return [double](($values | Measure-Object -Minimum).Minimum)
}

function Counter-Delta($Samples, [scriptblock]$Selector) {
    $values = @($Samples | ForEach-Object $Selector | Where-Object { $null -ne $_ })
    if ($values.Count -lt 2) { return $null }
    return [double]$values[-1] - [double]$values[0]
}

function Read-StatusCounts([string]$RawPath) {
    $counts = @{}
    if (-not (Test-Path -LiteralPath $RawPath)) { return $counts }
    $reader = [System.IO.StreamReader]::new($RawPath)
    try {
        while (-not $reader.EndOfStream) {
            $line = $reader.ReadLine()
            if ($line -notmatch '"metric":"http_reqs"') { continue }
            try { $point = $line | ConvertFrom-Json } catch { continue }
            if ($point.type -ne 'Point' -or $point.data.tags.scenario -ne 'steady' -or
                    $point.data.tags.lane -ne 'L0') { continue }
            $status = [string]$point.data.tags.status
            if (-not $counts.ContainsKey($status)) { $counts[$status] = 0 }
            $counts[$status]++
        }
    } finally {
        $reader.Dispose()
    }
    return $counts
}

$runs = @()
foreach ($run in $manifest.runs) {
    $caseDirectory = Join-Path $ResultsDirectory $run.case_id
    $summary = Get-Content -Raw -LiteralPath (Join-Path $caseDirectory 'k6-summary.json') | ConvertFrom-Json
    $samples = @(Get-Content -LiteralPath (Join-Path $caseDirectory 'resource-samples.jsonl') |
        ForEach-Object { $_ | ConvertFrom-Json })
    $workloadMetadata = $summary.PSObject.Properties['phase11']
    if (-not $workloadMetadata) { throw "Missing workload metadata in $($run.case_id) k6 summary" }
    $durationSeconds = [double]$workloadMetadata.Value.steadyDurationSeconds
    $totalRequests = Value (Metric $summary 'fctts_l0_requests') 'count'
    $duration = Metric $summary 'fctts_l0_duration'
    $errors = Metric $summary 'fctts_l0_errors'
    $endpoints = @()
    foreach ($endpoint in @('login','voice_list','voice_search','courseware_list','task_create','task_status')) {
        $endpointDuration = Metric $summary "fctts_l0_duration{endpoint:$endpoint}"
        $endpointErrors = Metric $summary "fctts_l0_errors{endpoint:$endpoint}"
        $endpointRequests = Metric $summary "fctts_l0_requests{endpoint:$endpoint}"
        $count = Value $endpointRequests 'count'
        $endpoints += [ordered]@{
            endpoint = $endpoint
            requests = $count
            share = if ($totalRequests -gt 0) { $count / $totalRequests } else { $null }
            p50_ms = Value $endpointDuration 'med'
            p95_ms = Value $endpointDuration 'p(95)'
            p99_ms = Value $endpointDuration 'p(99)'
            error_rate = Value $endpointErrors 'rate'
        }
    }
    $ordinaryEndpoints = @($endpoints | Where-Object { $_.endpoint -ne 'login' })
    $loginEndpoint = @($endpoints | Where-Object { $_.endpoint -eq 'login' })[0]
    $ordinarySloMet = @($ordinaryEndpoints | Where-Object {
        $_.requests -le 0 -or $_.p50_ms -ge 100 -or $_.p95_ms -ge 300 -or
        $_.p99_ms -ge 800 -or $_.error_rate -ge 0.01
    }).Count -eq 0
    $loginSloMet = $loginEndpoint.requests -gt 0 -and
        $loginEndpoint.p95_ms -lt 750 -and $loginEndpoint.error_rate -lt 0.01
    $thresholdsPassed = $true
    foreach ($metricProperty in $summary.metrics.PSObject.Properties) {
        if (-not $metricProperty.Value.thresholds) { continue }
        foreach ($threshold in $metricProperty.Value.thresholds.PSObject.Properties) {
            if (-not $threshold.Value.ok) { $thresholdsPassed = $false }
        }
    }
    $processWorkingSets = @()
    $processCpu = @()
    foreach ($sample in $samples) {
        $workingSet = 0.0
        $hasWorkingSet = $false
        foreach ($processProperty in $sample.processes.PSObject.Properties) {
            if ($processProperty.Value.alive -and $null -ne $processProperty.Value.working_set_bytes) {
                $workingSet += [double]$processProperty.Value.working_set_bytes
                $hasWorkingSet = $true
            }
            if ($null -ne $processProperty.Value.cpu_percent) {
                $processCpu += [double]$processProperty.Value.cpu_percent
            }
        }
        if ($hasWorkingSet) { $processWorkingSets += $workingSet }
    }
    $redisLatencies = @($samples | ForEach-Object { $_.redis.ping_latency_ms } |
        Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ })
    $runs += [ordered]@{
        case_id = $run.case_id
        users = [int]$run.users
        repetition = [int]$run.repetition
        k6_exit_code = [int]$run.k6_exit_code
        thresholds_passed = $thresholdsPassed
        requests = $totalRequests
        rps = if ($durationSeconds -gt 0) { $totalRequests / $durationSeconds } else { $null }
        p50_ms = Value $duration 'med'
        p95_ms = Value $duration 'p(95)'
        p99_ms = Value $duration 'p(99)'
        error_rate = Value $errors 'rate'
        http_status_counts = Read-StatusCounts (Join-Path $caseDirectory 'k6-raw.json')
        endpoints = $endpoints
        accepted_slo = [ordered]@{
            ordinary_api = [ordered]@{
                p50_ms_lt = 100
                p95_ms_lt = 300
                p99_ms_lt = 800
                error_rate_lt = 0.01
                met = $ordinarySloMet
            }
            login = [ordered]@{
                p95_ms_lt = 750
                error_rate_lt = 0.01
                met = $loginSloMet
            }
            note = 'k6 retains the original 300 ms threshold for every endpoint; accepted login SLO is evaluated separately here'
        }
        resources = [ordered]@{
            samples = $samples.Count
            scrape_failures = @($samples | Where-Object { $_.scrape_count -lt 2 }).Count
            host_cpu_p95_percent = Percentile @($samples | ForEach-Object { [double]$_.host.cpu_percent }) 0.95
            host_cpu_max_percent = Max-Number $samples { $_.host.cpu_percent }
            host_available_memory_min_mb = Min-Number $samples { $_.host.available_memory_mb }
            backend_cpu_max_percent = if ($processCpu.Count -gt 0) { [double](($processCpu | Measure-Object -Maximum).Maximum) } else { $null }
            backend_working_set_max_bytes = if ($processWorkingSets.Count -gt 0) { [double](($processWorkingSets | Measure-Object -Maximum).Maximum) } else { $null }
            jvm_heap_max_bytes = Max-Number $samples { $_.prometheus.jvm_heap_used_bytes }
            gc_pause_seconds = Counter-Delta $samples { $_.prometheus.jvm_gc_pause_seconds_sum }
            gc_pause_count = Counter-Delta $samples { $_.prometheus.jvm_gc_pause_seconds_count }
            jvm_threads_max = Max-Number $samples { $_.prometheus.jvm_threads_live }
            tomcat_busy_max = Max-Number $samples { $_.prometheus.tomcat_threads_busy }
            tomcat_current_max = Max-Number $samples { $_.prometheus.tomcat_threads_current }
            hikari_active_max = Max-Number $samples { $_.prometheus.hikari_active }
            hikari_pending_max = Max-Number $samples { $_.prometheus.hikari_pending }
            executor_active_max = Max-Number $samples { $_.prometheus.executor_active }
            executor_queued_max = Max-Number $samples { $_.prometheus.executor_queued }
            executor_rejected_delta = Counter-Delta $samples { $_.prometheus.task_rejected }
            redis_ping_p95_ms = Percentile $redisLatencies 0.95
            redis_memory_max_bytes = Max-Number $samples { $_.redis.used_memory }
            redis_keys_max = Max-Number $samples { $_.redis.keys }
            mysql_threads_connected_max = Max-Number $samples { $_.mysql.Threads_connected }
            mysql_threads_running_max = Max-Number $samples { $_.mysql.Threads_running }
            mysql_slow_queries_delta = Counter-Delta $samples { $_.mysql.Slow_queries }
            pending_tasks_max = Max-Number $samples {
                $property = $_.mysql.PSObject.Properties[$pendingTasksMetric]
                if ($property) { $property.Value }
            }
        }
    }
}

$result = [ordered]@{
    generated_at = [DateTime]::UtcNow.ToString('o')
    phase = $phaseNumber
    run_id = $manifest.run_id
    git_sha = $manifest.git_sha
    environment = $manifest
    runs = $runs
}
if ($phaseNumber -eq 16) {
    $functionalPath = Join-Path $ResultsDirectory 'phase16-multi-functional-raw.json'
    if (Test-Path -LiteralPath $functionalPath) {
        $functional = Get-Content -Raw -LiteralPath $functionalPath | ConvertFrom-Json
        $result.functional = $functional
    }
}
$outputPath = Join-Path $ResultsDirectory "${phaseLabel}-summary.json"
[System.IO.File]::WriteAllText($outputPath, ($result | ConvertTo-Json -Depth 12), [System.Text.UTF8Encoding]::new($false))
Write-Output $outputPath
