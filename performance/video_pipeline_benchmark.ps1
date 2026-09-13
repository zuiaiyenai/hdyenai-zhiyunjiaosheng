param(
    [string]$BaseUrl = "http://127.0.0.1:8081",
    [string]$ManagementUrl = "http://127.0.0.1:9091",
    [Parameter(Mandatory = $true)]
    [int]$BackendPid,
    [Parameter(Mandatory = $true)]
    [int]$AsrPid,
    [Parameter(Mandatory = $true)]
    [int]$TtsPid,
    [Parameter(Mandatory = $true)]
    [string[]]$Video,
    [string]$VoiceType = "longxiao",
    [bool]$IncludeSubtitles = $true,
    [int]$PollIntervalMilliseconds = 1000,
    [int]$TimeoutSeconds = 900,
    [string]$ResultsDir
)

$ErrorActionPreference = "Stop"

function Require-Loopback([string]$Value, [string]$Name) {
    $uri = [Uri]$Value
    if ($uri.Host -notin @("127.0.0.1", "localhost", "::1")) {
        throw "$Name must use a loopback address"
    }
}

function Get-Percentile([double[]]$Values, [double]$Percent) {
    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $ordered = @($Values | Sort-Object)
    $position = ($ordered.Count - 1) * $Percent / 100.0
    $lower = [Math]::Floor($position)
    $upper = [Math]::Ceiling($position)
    if ($lower -eq $upper) { return $ordered[$lower] }
    return $ordered[$lower] + ($ordered[$upper] - $ordered[$lower]) * ($position - $lower)
}

function Get-PrometheusSum([string]$Text, [string]$Metric, [string]$RequiredLabel) {
    $sum = 0.0
    $found = $false
    foreach ($line in $Text -split "`n") {
        if (-not $line.StartsWith($Metric)) { continue }
        if ($RequiredLabel -and -not $line.Contains($RequiredLabel)) { continue }
        if ($line -match '^.+\s+([-+0-9.eE]+)\s*$') {
            $sum += [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
            $found = $true
        }
    }
    if ($found) { return $sum }
    return $null
}

function Get-TempUsage {
    $count = 0
    $bytes = 0L
    $tempRoot = [IO.Path]::GetTempPath()
    foreach ($item in Get-ChildItem -LiteralPath $tempRoot -Force -ErrorAction SilentlyContinue) {
        if ($item.Name -notlike "fctts-*" -and $item.Name -notlike "video-voice-swap-*") { continue }
        if ($item.PSIsContainer) {
            $files = @(Get-ChildItem -LiteralPath $item.FullName -File -Recurse -Force -ErrorAction SilentlyContinue)
            $count += $files.Count
            $bytes += [long](($files | Measure-Object Length -Sum).Sum)
        } else {
            $count++
            $bytes += $item.Length
        }
    }
    return [ordered]@{ file_count = $count; bytes = $bytes }
}

function Get-ProcessSnapshot([int]$ProcessId) {
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $process) {
        return [ordered]@{ cpu_seconds = $null; rss_mib = $null; threads = $null }
    }
    return [ordered]@{
        cpu_seconds = $process.CPU
        rss_mib = $process.WorkingSet64 / 1MB
        threads = $process.Threads.Count
    }
}

function Get-ResourceSample([Diagnostics.Stopwatch]$Clock) {
    $metrics = ""
    try {
        $metrics = (Invoke-WebRequest -Uri "$ManagementUrl/actuator/prometheus" -TimeoutSec 3).Content
    } catch {}
    $heapUsedBytes = Get-PrometheusSum $metrics "jvm_memory_used_bytes" 'area="heap"'
    $drive = [IO.DriveInfo]::new("D")
    return [ordered]@{
        elapsed_seconds = $Clock.Elapsed.TotalSeconds
        timestamp = [DateTimeOffset]::UtcNow.ToString("o")
        backend = Get-ProcessSnapshot $BackendPid
        funasr = Get-ProcessSnapshot $AsrPid
        gpt_sovits = Get-ProcessSnapshot $TtsPid
        heap_used_mib = if ($null -eq $heapUsedBytes) { $null } else { $heapUsedBytes / 1MB }
        gc_pause_count = Get-PrometheusSum $metrics "jvm_gc_pause_seconds_count" ""
        gc_pause_seconds = Get-PrometheusSum $metrics "jvm_gc_pause_seconds_sum" ""
        task_queue_depth = Get-PrometheusSum $metrics "fctts_task_queue_depth" ""
        drive_d_free_mib = $drive.AvailableFreeSpace / 1MB
        temp = Get-TempUsage
    }
}

function Get-ProcessSummary($Samples, [string]$Name) {
    $cpu = @()
    for ($index = 1; $index -lt $Samples.Count; $index++) {
        $previous = $Samples[$index - 1]
        $current = $Samples[$index]
        $before = $previous.$Name.cpu_seconds
        $after = $current.$Name.cpu_seconds
        $elapsed = $current.elapsed_seconds - $previous.elapsed_seconds
        if ($null -ne $before -and $null -ne $after -and $elapsed -gt 0) {
            $cpu += 100.0 * ($after - $before) / $elapsed
        }
    }
    $rss = @($Samples | ForEach-Object { $_.$Name.rss_mib } | Where-Object { $null -ne $_ })
    $threads = @($Samples | ForEach-Object { $_.$Name.threads } | Where-Object { $null -ne $_ })
    return [ordered]@{
        cpu_p95_percent = Get-Percentile $cpu 95
        cpu_peak_percent = if ($cpu.Count) { ($cpu | Measure-Object -Maximum).Maximum } else { $null }
        rss_peak_mib = if ($rss.Count) { ($rss | Measure-Object -Maximum).Maximum } else { $null }
        threads_peak = if ($threads.Count) { ($threads | Measure-Object -Maximum).Maximum } else { $null }
    }
}

function Invoke-Json([Net.Http.HttpClient]$Client, [string]$Path) {
    $response = $Client.GetAsync("$BaseUrl$Path").GetAwaiter().GetResult()
    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "GET $Path failed with HTTP $([int]$response.StatusCode): $body"
    }
    return $body | ConvertFrom-Json
}

Require-Loopback $BaseUrl "BaseUrl"
Require-Loopback $ManagementUrl "ManagementUrl"
if (-not $env:VIDEO_TEST_USERNAME -or -not $env:VIDEO_TEST_PASSWORD) {
    throw "VIDEO_TEST_USERNAME and VIDEO_TEST_PASSWORD are required"
}
if ($PollIntervalMilliseconds -lt 250 -or $TimeoutSeconds -lt 1) {
    throw "poll interval or timeout is invalid"
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$targetRoot = (Resolve-Path (Join-Path $projectRoot "target")).Path
$timestamp = [DateTimeOffset]::UtcNow.ToString("yyyyMMddHHmmss")
$runRoot = if ($ResultsDir) { [IO.Path]::GetFullPath($ResultsDir) } else {
    Join-Path $targetRoot "phase16-video-$timestamp"
}
if (-not $runRoot.StartsWith($targetRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw "ResultsDir must be under target"
}
if (Test-Path -LiteralPath $runRoot) {
    if (Get-ChildItem -LiteralPath $runRoot -Force) { throw "ResultsDir is not empty" }
} else {
    New-Item -ItemType Directory -Path $runRoot | Out-Null
}

$videos = foreach ($path in $Video) {
    $item = Get-Item -LiteralPath $path
    if (-not $item -or $item.PSIsContainer) { throw "Video not found: $path" }
    $item
}
foreach ($processId in @($BackendPid, $AsrPid, $TtsPid)) {
    if (-not (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
        throw "Required process is not running: $processId"
    }
}

$handler = [Net.Http.HttpClientHandler]::new()
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
$evidence = [ordered]@{
    phase = "16.5"
    started_at = [DateTimeOffset]::UtcNow.ToString("o")
    scope = "authenticated Spring upload -> durable MySQL task -> OSS -> FFmpeg -> real FunASR -> real GPT-SoVITS -> FFmpeg -> OSS -> streamed result download"
    credentials_recorded = $false
    process_ids = [ordered]@{ backend = $BackendPid; funasr = $AsrPid; gpt_sovits = $TtsPid }
    protocol = [ordered]@{
        voice_type = $VoiceType
        include_subtitles = $IncludeSubtitles
        poll_interval_milliseconds = $PollIntervalMilliseconds
        timeout_seconds = $TimeoutSeconds
    }
    cases = @()
}

try {
    $loginBody = @{ username = $env:VIDEO_TEST_USERNAME; password = $env:VIDEO_TEST_PASSWORD } |
        ConvertTo-Json -Compress
    $loginContent = [Net.Http.StringContent]::new($loginBody, [Text.Encoding]::UTF8, "application/json")
    $loginResponse = $client.PostAsync("$BaseUrl/user/login", $loginContent).GetAwaiter().GetResult()
    $loginJson = $loginResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if (-not $loginResponse.IsSuccessStatusCode -or -not $loginJson.token) {
        throw "Login failed with HTTP $([int]$loginResponse.StatusCode)"
    }
    $client.DefaultRequestHeaders.Authorization =
        [Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $loginJson.token)

    foreach ($videoFile in $videos) {
        $caseClock = [Diagnostics.Stopwatch]::StartNew()
        $samples = @()
        $tempBefore = Get-TempUsage
        $multipart = [Net.Http.MultipartFormDataContent]::new()
        $videoStream = [IO.File]::OpenRead($videoFile.FullName)
        $videoContent = [Net.Http.StreamContent]::new($videoStream)
        $videoContent.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new("video/mp4")
        $multipart.Add($videoContent, "video", $videoFile.Name)
        $multipart.Add([Net.Http.StringContent]::new($VoiceType), "voiceType")
        $multipart.Add([Net.Http.StringContent]::new($IncludeSubtitles.ToString().ToLowerInvariant()), "includeSubtitles")
        try {
            $uploadResponse = $client.PostAsync("$BaseUrl/video_voice_swap/process", $multipart).GetAwaiter().GetResult()
            $uploadBody = $uploadResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        } finally {
            $multipart.Dispose()
            $videoStream.Dispose()
        }
        $uploadSeconds = $caseClock.Elapsed.TotalSeconds
        if ([int]$uploadResponse.StatusCode -ne 202) {
            throw "Video upload failed with HTTP $([int]$uploadResponse.StatusCode): $uploadBody"
        }
        $submission = $uploadBody | ConvertFrom-Json
        $taskId = $submission.taskId
        $samples += Get-ResourceSample $caseClock
        $task = $null
        do {
            Start-Sleep -Milliseconds $PollIntervalMilliseconds
            $task = Invoke-Json $client "/api/tasks/$taskId"
            $samples += Get-ResourceSample $caseClock
            if ($caseClock.Elapsed.TotalSeconds -gt $TimeoutSeconds) {
                throw "Task did not reach a terminal state within $TimeoutSeconds seconds"
            }
        } while ($task.status -notin @("SUCCESS", "FAILED", "CANCELLED"))

        $resultPath = $null
        $resultBytes = 0L
        $resultSha256 = $null
        $probe = $null
        if ($task.status -eq "SUCCESS") {
            $resultPath = Join-Path $runRoot ("result-{0}.mp4" -f $videoFile.BaseName)
            $download = $client.GetAsync("$BaseUrl/api/tasks/$taskId/result",
                [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
            if (-not $download.IsSuccessStatusCode) {
                throw "Result download failed with HTTP $([int]$download.StatusCode)"
            }
            $sourceStream = $download.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
            $destination = [IO.File]::Create($resultPath)
            try {
                $sourceStream.CopyTo($destination)
            } finally {
                $destination.Dispose()
                $sourceStream.Dispose()
                $download.Dispose()
            }
            $resultBytes = (Get-Item -LiteralPath $resultPath).Length
            $resultSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resultPath).Hash
            $probeJson = & ffprobe -v error -show_entries format=duration,size -show_entries stream=codec_name,codec_type `
                -of json $resultPath
            if ($LASTEXITCODE -ne 0) { throw "ffprobe failed for $resultPath" }
            $probe = ($probeJson -join "`n") | ConvertFrom-Json
        }
        $caseClock.Stop()
        Start-Sleep -Milliseconds 500
        $tempAfter = Get-TempUsage
        $heap = @($samples | ForEach-Object { $_.heap_used_mib } | Where-Object { $null -ne $_ })
        $tempBytes = @($samples | ForEach-Object { $_.temp.bytes })
        $freeDisk = @($samples | ForEach-Object { $_.drive_d_free_mib })
        $gcCount = @($samples | ForEach-Object { $_.gc_pause_count } | Where-Object { $null -ne $_ })
        $gcSeconds = @($samples | ForEach-Object { $_.gc_pause_seconds } | Where-Object { $null -ne $_ })
        $evidence.cases += [ordered]@{
            input = [ordered]@{
                name = $videoFile.Name
                bytes = $videoFile.Length
                sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $videoFile.FullName).Hash
            }
            upload_http_status = [int]$uploadResponse.StatusCode
            upload_seconds = $uploadSeconds
            task_id = $taskId
            duplicate_submission = [bool]$submission.duplicate
            terminal_status = $task.status
            attempts = $task.attempts
            error_code = $task.errorCode
            error_message = $task.errorMessage
            end_to_end_seconds = $caseClock.Elapsed.TotalSeconds
            output = [ordered]@{
                bytes = $resultBytes
                sha256 = $resultSha256
                ffprobe = $probe
            }
            resources = [ordered]@{
                sample_count = $samples.Count
                backend = Get-ProcessSummary $samples "backend"
                funasr = Get-ProcessSummary $samples "funasr"
                gpt_sovits = Get-ProcessSummary $samples "gpt_sovits"
                heap_used_peak_mib = if ($heap.Count) { ($heap | Measure-Object -Maximum).Maximum } else { $null }
                gc_pause_count_delta = if ($gcCount.Count) { $gcCount[-1] - $gcCount[0] } else { $null }
                gc_pause_seconds_delta = if ($gcSeconds.Count) { $gcSeconds[-1] - $gcSeconds[0] } else { $null }
                temporary_bytes_peak = if ($tempBytes.Count) { ($tempBytes | Measure-Object -Maximum).Maximum } else { $null }
                drive_d_free_mib_min = if ($freeDisk.Count) { ($freeDisk | Measure-Object -Minimum).Minimum } else { $null }
                temp_before = $tempBefore
                temp_after = $tempAfter
            }
            samples = $samples
        }
    }
} finally {
    $client.Dispose()
    $handler.Dispose()
}

$evidence.finished_at = [DateTimeOffset]::UtcNow.ToString("o")
$output = Join-Path $runRoot "phase16-video-raw-evidence.json"
[IO.File]::WriteAllText($output, ($evidence | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
$digest = (Get-FileHash -Algorithm SHA256 -LiteralPath $output).Hash
Write-Output (@{ results = $output; sha256 = $digest } | ConvertTo-Json -Compress)
