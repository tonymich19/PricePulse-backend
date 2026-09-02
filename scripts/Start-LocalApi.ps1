[CmdletBinding()]
param(
    [string]$OpenAiApiKey,
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$FirebaseServiceAccountPath,
    [ValidateRange(1, 1000)]
    [int]$MonthlyFreeCredits = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function ConvertTo-PlainText {
    param([Parameter(Mandatory = $true)][Security.SecureString]$Value)

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Invoke-Docker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed: docker $($Arguments -join ' ')"
    }
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$secretsDirectory = Join-Path $repositoryRoot 'secrets'
$openAiKeyFile = Join-Path $secretsDirectory 'openai-api-key'
$firebaseTargetFile = Join-Path $secretsDirectory 'firebase-service-account.json'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker Desktop is required. Install it and start Docker Desktop before running this script.'
}

Invoke-Docker -Arguments @('info', '--format', '{{.ServerVersion}}')

if ([string]::IsNullOrWhiteSpace($OpenAiApiKey)) {
    $OpenAiApiKey = ConvertTo-PlainText (Read-Host 'OpenAI API key (it will not be shown)' -AsSecureString)
}
if ([string]::IsNullOrWhiteSpace($OpenAiApiKey)) {
    throw 'An OpenAI API key is required.'
}

if ([string]::IsNullOrWhiteSpace($FirebaseServiceAccountPath)) {
    $FirebaseServiceAccountPath = Read-Host 'Full path to the Firebase service-account JSON file'
}
if (-not (Test-Path -LiteralPath $FirebaseServiceAccountPath -PathType Leaf)) {
    throw 'The Firebase service-account JSON file was not found.'
}

New-Item -ItemType Directory -Force -Path $secretsDirectory | Out-Null
Set-Content -LiteralPath $openAiKeyFile -Value $OpenAiApiKey -NoNewline -Encoding ascii
$firebaseSourceFile = (Resolve-Path -LiteralPath $FirebaseServiceAccountPath).Path
if (-not [string]::Equals($firebaseSourceFile, $firebaseTargetFile, [StringComparison]::OrdinalIgnoreCase)) {
    Copy-Item -LiteralPath $firebaseSourceFile -Destination $firebaseTargetFile -Force
}

$env:OPENAI_API_KEY_FILE = $openAiKeyFile
$env:FIREBASE_SERVICE_ACCOUNT_FILE = $firebaseTargetFile
$env:MONTHLY_FREE_CREDITS = $MonthlyFreeCredits.ToString([Globalization.CultureInfo]::InvariantCulture)

Push-Location $repositoryRoot
try {
    Invoke-Docker -Arguments @('compose', 'up', '--build', '--detach')
}
finally {
    Pop-Location
}

$healthUri = 'http://localhost:8080/health'
for ($attempt = 1; $attempt -le 45; $attempt++) {
    try {
        $health = Invoke-RestMethod -Uri $healthUri -Method Get -TimeoutSec 2
        if ($health -eq 'OK') {
            Write-Host "PricePulse API is ready at $healthUri"
            Write-Host 'Next: run scripts/Test-ReceiptAnalysis.ps1 with a Firebase ID token and receipt image.'
            exit 0
        }
    }
    catch {
        Start-Sleep -Seconds 2
    }
}

Write-Host 'The API did not become healthy. Recent container logs:'
Push-Location $repositoryRoot
try {
    & docker compose logs --tail 100 api
}
finally {
    Pop-Location
}
throw 'PricePulse API did not become ready within 90 seconds.'
