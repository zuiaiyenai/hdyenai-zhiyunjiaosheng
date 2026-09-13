param(
    [ValidateSet(30, 60)]
    [int]$DurationMinutes = 30
)

$ErrorActionPreference = "Stop"
$runner = Join-Path $PSScriptRoot "run_phase11.ps1"
& $runner `
    -RunPhase phase13 `
    -UserLevels 150 `
    -Repetitions 1 `
    -WarmupDuration "2m" `
    -SteadyDuration "${DurationMinutes}m" `
    -StabilizationSeconds 60 `
    -CollectorTailSeconds 300
