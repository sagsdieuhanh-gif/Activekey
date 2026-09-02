$ErrorActionPreference = "Stop"
$Build = "V2.2.5-SIGNATURE-EXPORT-STORAGE-FIX-R2"
$nonce = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

$urls = @(
  "https://raw.githubusercontent.com/sagsdieuhanh-gif/E-REPORT-SAGS/main/version.json?t=$nonce",
  "https://sagsdieuhanh-gif.github.io/E-REPORT-SAGS/version.json?t=$nonce"
)

foreach ($url in $urls) {
  Write-Host ""
  Write-Host $url -ForegroundColor Cyan
  try {
    $v = Invoke-RestMethod -Uri $url -Headers @{"Cache-Control"="no-cache"}
    Write-Host ("build: " + $v.build)
    Write-Host ("version: " + $v.displayVersion)
    if ($v.build -ne $Build) {
      Write-Host "NOT READY" -ForegroundColor Red
    } else {
      Write-Host "OK" -ForegroundColor Green
    }
  } catch {
    Write-Host $_ -ForegroundColor Red
  }
}
