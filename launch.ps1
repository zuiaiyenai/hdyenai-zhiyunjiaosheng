param(
    [ValidateSet("auto", "db", "nodb")]
    [string]$Mode = "auto"
)

$ErrorActionPreference = "Stop"
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectDir

$localConfig = Join-Path $projectDir "config\application-local.yml"
$legacyLocalConfig = Join-Path $projectDir "config\application-local.properties"
$maven = Get-Command "mvn" -ErrorAction SilentlyContinue

if (-not $maven) {
    throw "Maven was not found. Install Maven 3.9+ and add its bin directory to PATH."
}

$gptSovitsDir = if ($env:GPT_SOVITS_HOME) {
    $env:GPT_SOVITS_HOME
} else {
    "D:\BaiduNetdiskDownload\GPT-SoVITS-v2-240821"
}
$gptSovitsPython = Join-Path $gptSovitsDir "runtime\python.exe"
$gptSovitsApi = Join-Path $gptSovitsDir "api_v2.py"
$speechServiceOnline = $false
try {
    $speechResponse = Invoke-WebRequest `
        -Uri "http://127.0.0.1:9880/tts" `
        -Method Get `
        -TimeoutSec 2 `
        -SkipHttpErrorCheck
    $speechServiceOnline = $speechResponse.StatusCode -ne 404
} catch {
    $speechServiceOnline = $false
}

if (-not $speechServiceOnline -and (Test-Path $gptSovitsPython) -and (Test-Path $gptSovitsApi)) {
    $speechLogDir = Join-Path $projectDir "logs"
    New-Item -ItemType Directory -Force -Path $speechLogDir | Out-Null
    Start-Process -FilePath $gptSovitsPython `
        -ArgumentList @("api_v2.py", "-a", "127.0.0.1", "-p", "9880", "-c", "GPT_SoVITS/configs/tts_infer.yaml") `
        -WorkingDirectory $gptSovitsDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $speechLogDir "gpt-sovits.out.log") `
        -RedirectStandardError (Join-Path $speechLogDir "gpt-sovits.err.log")
    Write-Host "GPT-SoVITS is starting from $gptSovitsDir on http://127.0.0.1:9880"
}

$asrModelRoot = Join-Path $gptSovitsDir "tools\asr\models"
$asrServer = Join-Path $projectDir "scripts\asr_server.py"
$asrModelDirs = @(
    "speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch",
    "speech_fsmn_vad_zh-cn-16k-common-pytorch",
    "punc_ct-transformer_zh-cn-common-vocab272727-pytorch"
)

function Test-AsrService {
    try {
        $health = Invoke-RestMethod `
            -Uri "http://127.0.0.1:9977/health" `
            -Method Get `
            -TimeoutSec 2
        return $health.status -eq "UP"
    } catch {
        return $false
    }
}

$asrModelsReady = $true
foreach ($modelDir in $asrModelDirs) {
    if (-not (Test-Path (Join-Path $asrModelRoot "$modelDir\model.pt"))) {
        $asrModelsReady = $false
        Write-Warning "Missing FunASR model: $modelDir"
    }
}

$asrServiceOnline = Test-AsrService
if (-not $asrServiceOnline -and (Test-Path $gptSovitsPython) -and (Test-Path $asrServer)) {
    if ($asrModelsReady) {
        $speechLogDir = Join-Path $projectDir "logs"
        New-Item -ItemType Directory -Force -Path $speechLogDir | Out-Null
        $asrProcess = Start-Process -FilePath $gptSovitsPython `
            -ArgumentList @($asrServer, "--model-root", $asrModelRoot, "--host", "127.0.0.1", "--port", "9977") `
            -WorkingDirectory $gptSovitsDir `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $speechLogDir "asr.out.log") `
            -RedirectStandardError (Join-Path $speechLogDir "asr.err.log") `
            -PassThru
        Write-Host "Waiting for local FunASR on http://127.0.0.1:9977 ..."
        for ($attempt = 0; $attempt -lt 60 -and -not $asrProcess.HasExited; $attempt++) {
            if (Test-AsrService) {
                $asrServiceOnline = $true
                break
            }
            Start-Sleep -Seconds 2
        }
    }
}

if ($asrServiceOnline) {
    Write-Host "Local FunASR is ready."
} else {
    Write-Warning "Local FunASR is unavailable. Speaking evaluation will return ASR_UNAVAILABLE instead of generating scores. Check logs/asr.err.log."
}

if ($Mode -eq "auto") {
    if (Test-Path $localConfig) {
        $Mode = "db"
    } elseif (Test-Path $legacyLocalConfig) {
        throw "Legacy config/application-local.properties was found but is no longer loaded. Copy config/application-local.yml.example to config/application-local.yml and migrate the local values."
    } else {
        $Mode = "nodb"
    }
}

if ($Mode -eq "db") {
    if (-not (Test-Path $localConfig)) {
        throw "Missing config/application-local.yml. Copy config/application-local.yml.example and configure it first."
    }
    Write-Host "Starting application in database mode..."
    $localConfigUri = ([System.Uri]$localConfig).AbsoluteUri
    $previousAdditionalLocation = $env:SPRING_CONFIG_ADDITIONAL_LOCATION
    try {
        $env:SPRING_CONFIG_ADDITIONAL_LOCATION = $localConfigUri
        & $maven.Source spring-boot:run "-Dspring-boot.run.profiles=local"
    } finally {
        if ($null -eq $previousAdditionalLocation) {
            Remove-Item Env:SPRING_CONFIG_ADDITIONAL_LOCATION -ErrorAction SilentlyContinue
        } else {
            $env:SPRING_CONFIG_ADDITIONAL_LOCATION = $previousAdditionalLocation
        }
    }
} else {
    Write-Host "Local database config not found. Starting no-database demo mode..."
    Write-Host "Demo accounts: admin/admin123 or demo/demo123"
    & $maven.Source spring-boot:run "-Dspring-boot.run.profiles=nodb"
}
