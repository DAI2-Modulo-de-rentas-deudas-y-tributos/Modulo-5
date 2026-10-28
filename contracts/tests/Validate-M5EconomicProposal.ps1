# Requires PowerShell 7+ (Test-Json -SchemaFile). Validates proposal artifacts only.
$ErrorActionPreference = 'Stop'
$contractsRoot = Split-Path -Parent $PSScriptRoot
$schemaPath = Join-Path $contractsRoot 'schemas/m5-economic-outbound.proposed.schema.json'
$examplesRoot = Join-Path $contractsRoot 'events/examples/m5-economic'
$contractPath = Join-Path $contractsRoot 'events/M5_ECONOMIC_OUTBOUND_UUID_DRAFT.md'

function Read-Example([string] $name) {
    Get-Content -LiteralPath (Join-Path $examplesRoot $name) -Raw | ConvertFrom-Json -AsHashtable
}

function Assert-Invalid([string] $name, [System.Collections.IDictionary] $example) {
    $json = $example | ConvertTo-Json -Depth 20 -Compress
    if (Test-Json -Json $json -SchemaFile $schemaPath -ErrorAction SilentlyContinue) {
        throw "El schema aceptó un caso inválido: $name"
    }
}

$files = @(Get-ChildItem -LiteralPath $examplesRoot -Filter '*.proposed.json')
if ($files.Count -ne 8) { throw "Se esperaban ocho ejemplos; hay $($files.Count)" }
$eventIds = [System.Collections.Generic.HashSet[string]]::new()
foreach ($file in $files) {
    if (-not (Test-Json -LiteralPath $file.FullName -SchemaFile $schemaPath -ErrorAction Stop)) {
        throw "El ejemplo no valida: $($file.Name)"
    }
    $event = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json -AsHashtable
    if (-not $eventIds.Add($event.eventId)) { throw "eventId duplicado: $($event.eventId)" }
    if ($event.data.originType -eq 'TRAFFIC_FINE' -and $event.data.originId -ne $event.data.infractionId) {
        throw "originId e infractionId difieren: $($file.Name)"
    }
}

$inlineExamples = [regex]::Matches((Get-Content -LiteralPath $contractPath -Raw), '(?s)```json\s*(.*?)\s*```')
if ($inlineExamples.Count -ne 4) { throw "Se esperaban cuatro ejemplos JSON en el contrato; hay $($inlineExamples.Count)" }
foreach ($example in $inlineExamples) {
    if (-not (Test-Json -Json $example.Groups[1].Value -SchemaFile $schemaPath -ErrorAction Stop)) {
        throw 'Un ejemplo JSON del contrato no valida contra el schema propuesto'
    }
}

$m4 = Read-Example 'm4-payment-registered-null-correlation.proposed.json'
$reversal = Read-Example 'm4-payment-reversed.proposed.json'
if ($null -ne $m4.correlationId) { throw 'El caso M4 sin correlación ya no es null' }
if ($m4.data.paymentId -ne $reversal.data.paymentId -or
    $m4.data.debtId -ne $reversal.data.debtId -or
    $m4.data.originId -ne $reversal.data.originId -or
    [decimal] $m4.data.amountApplied -ne [decimal] $reversal.data.reversedAmount -or
    [decimal] $m4.data.remainingBalance + [decimal] $reversal.data.reversedAmount -ne [decimal] $reversal.data.remainingBalance) {
    throw 'Pago y reversión M4 no preservan IDs, porción y saldo'
}

$distributedM4 = Read-Example 'distributed-m4-payment-registered.proposed.json'
$distributedM7 = Read-Example 'distributed-m7-payment-registered.proposed.json'
if ($distributedM4.data.paymentId -ne $distributedM7.data.paymentId -or
    $distributedM4.data.debtId -eq $distributedM7.data.debtId -or
    $distributedM4.eventId -eq $distributedM7.eventId -or
    [decimal] $distributedM4.data.amountApplied + [decimal] $distributedM7.data.amountApplied -ne 100 -or
    [decimal] $distributedM4.data.remainingBalance -ne 60 -or
    [decimal] $distributedM7.data.remainingBalance -ne 40) {
    throw 'El ejemplo de pago distribuido no conserva porciones e identidades'
}

$m7Payment = Read-Example 'm7-payment-registered.proposed.json'
$m7Settled = Read-Example 'm7-debt-settled.proposed.json'
if ($m7Payment.data.debtId -ne $m7Settled.data.debtId -or
    $m7Payment.data.originId -ne $m7Settled.data.originId -or
    [decimal] $m7Payment.data.remainingBalance -ne 0 -or
    [decimal] $m7Settled.data.outstandingBalance -ne 0) {
    throw 'Pago total y cancelación M7 no coinciden'
}

$invalid = Read-Example 'm7-payment-registered.proposed.json'
$invalid.data.debtId = 'debt-123'
Assert-Invalid 'ID prefijado legado' $invalid

$invalid = Read-Example 'm7-payment-registered.proposed.json'
[void] $invalid.data.Remove('infractionId')
Assert-Invalid 'infractionId faltante en M7' $invalid

$invalid = Read-Example 'm4-payment-registered-null-correlation.proposed.json'
$invalid.data.infractionId = 'b63caa31-b8e1-4981-8348-ce40c0cb4490'
Assert-Invalid 'infractionId extra en M4' $invalid

$invalid = Read-Example 'm4-payment-registered-null-correlation.proposed.json'
$invalid.data.amountApplied = -1
Assert-Invalid 'importe negativo' $invalid

$invalid = Read-Example 'm4-payment-registered-null-correlation.proposed.json'
$invalid.data.amountApplied = 1.001
Assert-Invalid 'más de dos decimales monetarios' $invalid

$invalid = Read-Example 'm4-payment-registered-null-correlation.proposed.json'
$invalid.data.originType = 'MUNICIPAL_TAX'
Assert-Invalid 'pago municipal aún no propuesto' $invalid

$invalid = Read-Example 'm4-payment-registered-null-correlation.proposed.json'
$invalid.data.paidAt = '2026-10-26T10:00:00'
Assert-Invalid 'fecha sin zona' $invalid

$invalid = Read-Example 'm8-municipal-debt-settled-blocked.proposed.json'
$invalid.data.citizenId = $null
Assert-Invalid 'citizenId null municipal' $invalid

$invalid = Read-Example 'm8-municipal-debt-settled-blocked.proposed.json'
[void] $invalid.data.Remove('citizenId')
Assert-Invalid 'citizenId ausente municipal' $invalid

$invalid = Read-Example 'm8-municipal-debt-settled-blocked.proposed.json'
$invalid.data.originId = 'liq-01001'
Assert-Invalid 'originId municipal no UUID' $invalid

$invalid = Read-Example 'm7-debt-settled.proposed.json'
$invalid.data.outstandingBalance = 1
Assert-Invalid 'debtSettled con saldo positivo' $invalid

$invalid = Read-Example 'm4-debt-settled-no-citizen.proposed.json'
$invalid.data.citizenId = $null
Assert-Invalid 'citizenId null explícito' $invalid

$invalid = Read-Example 'm4-payment-reversed.proposed.json'
$invalid.extra = 'fuera del envelope Core'
Assert-Invalid 'campo extra en envelope' $invalid

$invalid = Read-Example 'm4-payment-reversed.proposed.json'
$invalid.correlationId = 'no-es-uuid'
Assert-Invalid 'correlationId no UUID' $invalid

Write-Output "OK: $($files.Count) ejemplos en archivo y $($inlineExamples.Count) inline válidos; 14 variantes inválidas rechazadas; consistencia económica verificada."
