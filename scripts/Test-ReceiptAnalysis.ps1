[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$ImagePath,
    [string]$FirebaseIdToken,
    [ValidatePattern('^[A-Za-z0-9_-]{1,128}$')]
    [string]$IdempotencyKey = ("local-" + [Guid]::NewGuid().ToString('N')),
    [string]$BaseUrl = 'http://localhost:8080'
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

$resolvedImagePath = (Resolve-Path -LiteralPath $ImagePath).Path
$image = Get-Item -LiteralPath $resolvedImagePath
if ($image.Length -gt 5MB) {
    throw 'The receipt image must be 5 MiB or smaller.'
}

$mediaType = switch ($image.Extension.ToLowerInvariant()) {
    '.jpg' { 'image/jpeg'; break }
    '.jpeg' { 'image/jpeg'; break }
    '.png' { 'image/png'; break }
    default { throw 'The receipt image must be a .jpg, .jpeg, or .png file.' }
}

if ([string]::IsNullOrWhiteSpace($FirebaseIdToken)) {
    $FirebaseIdToken = ConvertTo-PlainText (Read-Host 'Firebase ID token for a test user (it will not be shown)' -AsSecureString)
}
if ([string]::IsNullOrWhiteSpace($FirebaseIdToken)) {
    throw 'A Firebase ID token is required. A Firebase service-account JSON is not an ID token.'
}

$endpoint = "$($BaseUrl.TrimEnd('/'))/v1/receipt-analyses"
$arguments = @(
    '--silent', '--show-error', '--include', '--request', 'POST', $endpoint,
    '--header', "Authorization: Bearer $FirebaseIdToken",
    '--header', "Idempotency-Key: $IdempotencyKey",
    '--form', "image=@$resolvedImagePath;type=$mediaType"
)

Write-Host "Sending receipt with idempotency key: $IdempotencyKey"
& curl.exe @arguments
if ($LASTEXITCODE -ne 0) {
    throw "The POST request failed before the API returned a response (curl exit code $LASTEXITCODE)."
}

Write-Host "`nTo poll the result, call: GET $endpoint/$IdempotencyKey"
