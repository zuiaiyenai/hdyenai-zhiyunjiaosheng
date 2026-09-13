param(
    [ValidateSet('30m', '60m')]
    [string]$SteadyDuration = '30m'
)

$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'run_phase11.ps1'
& $runner `
    -RunPhase phase16-soak `
    -UserLevels 100 `
    -Repetitions 1 `
    -WarmupDuration '2m' `
    -SteadyDuration $SteadyDuration `
    -StabilizationSeconds 60 `
    -CollectorTailSeconds 300 `
    -TaskSharePercent 2
