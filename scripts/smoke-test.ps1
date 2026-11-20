# End-to-end smoke test (Phases 1-2) against a running backend (Docker or local).
# Usage: .\scripts\smoke-test.ps1 [-Base http://localhost:8080]
param([string]$Base = "http://localhost:8080")
$ErrorActionPreference = "Stop"
$pass = if ($env:PAYSURE_DEMO_PASSWORD) { $env:PAYSURE_DEMO_PASSWORD } else { "Sandbox@123" }

Write-Host "1. health"; Invoke-RestMethod "$Base/actuator/health" | ConvertTo-Json -Compress
Write-Host "2. login (ananya)"
$login = Invoke-RestMethod -Method Post "$Base/api/auth/login" -ContentType "application/json" `
  -Body (@{ email = "ananya@example.com"; password = $pass } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
Write-Host "3. accounts"; (Invoke-RestMethod "$Base/api/accounts" -Headers $headers)[0].vpas[0].vpa
Write-Host "4. resolve payee"; Invoke-RestMethod "$Base/api/vpas/rahul@paysure/resolve" -Headers $headers | ConvertTo-Json -Compress
Write-Host "5. no token -> 401"
try { Invoke-RestMethod "$Base/api/accounts" | Out-Null; throw "expected 401" }
catch { if ($_.Exception.Response.StatusCode.value__ -ne 401) { throw } else { "ok" } }
Write-Host "6. USER -> dev tools 403"
try {
  Invoke-RestMethod -Method Post "$Base/api/dev/accounts/00000000-0000-0000-0000-000000000000/top-up" `
    -Headers $headers -ContentType "application/json" -Body '{"amountPaise":100}' | Out-Null; throw "expected 403"
} catch { if ($_.Exception.Response.StatusCode.value__ -ne 403) { throw } else { "ok" } }
Write-Host "7. pay Rs 1.00 ananya -> rahul with an Idempotency-Key"
$key = "smoke-" + [guid]::NewGuid()
$body = '{"payerVpa":"ananya@paysure","payeeVpa":"rahul@paysure","amountPaise":100,"note":"smoke"}'
$payHeaders = $headers.Clone(); $payHeaders["Idempotency-Key"] = $key
$first = Invoke-WebRequest -UseBasicParsing -Method Post "$Base/api/payments" -Headers $payHeaders -ContentType "application/json" -Body $body
($first.Content | ConvertFrom-Json).status
Write-Host "8. same key again -> 200, same payment"
$again = Invoke-WebRequest -UseBasicParsing -Method Post "$Base/api/payments" -Headers $payHeaders -ContentType "application/json" -Body $body
if ($again.StatusCode -ne 200 -or ($again.Content | ConvertFrom-Json).id -ne ($first.Content | ConvertFrom-Json).id) { throw "replay mismatch" } else { "ok" }
Write-Host "9. same key, different amount -> 409"
try {
  Invoke-RestMethod -Method Post "$Base/api/payments" -Headers $payHeaders -ContentType "application/json" `
    -Body '{"payerVpa":"ananya@paysure","payeeVpa":"rahul@paysure","amountPaise":999}' | Out-Null; throw "expected 409"
} catch { if ($_.Exception.Response.StatusCode.value__ -ne 409) { throw } else { "ok" } }
Write-Host "Smoke test passed."
