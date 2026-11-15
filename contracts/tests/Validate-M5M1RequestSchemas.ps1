$ErrorActionPreference = 'Stop'
$schemaRoot = Join-Path $PSScriptRoot '..\schemas\m5-to-m1'
$examplesPath = Join-Path $PSScriptRoot '..\..\docs\integrations\M1_PAYLOADS_LOCALES.md'
$schemaByEvent = @{
    exemptionRequested = 'exemptionRequested.data.schema.json'
    updateExemptionStatus = 'updateExemptionStatus.data.schema.json'
    paymentPlanRequested = 'paymentPlanRequested.data.schema.json'
    updatePaymentPlanStatus = 'updatePaymentPlanStatus.data.schema.json'
}
$negativeCases = 0

function Copy-Data($data) {
    return ($data | ConvertTo-Json -Depth 20 -Compress | ConvertFrom-Json -AsHashtable)
}

function Test-Data([string] $eventType, $data) {
    $json = ConvertTo-Json -InputObject $data -Depth 20 -Compress
    try {
        return Test-Json -Json $json -SchemaFile (Join-Path $schemaRoot $schemaByEvent[$eventType]) -ErrorAction Stop
    } catch {
        return $false
    }
}

function Assert-Invalid([string] $label, [string] $eventType, $data) {
    if (Test-Data $eventType $data) { throw "El schema aceptó el caso inválido: $label" }
    $script:negativeCases++
}

function Assert-Absent([string] $label, $data, [string[]] $properties) {
    foreach ($property in $properties) {
        if ($data.Contains($property)) { throw "Jackson no omitió $property en $label" }
    }
}

$schemaFiles = @($schemaByEvent.Values | Sort-Object -Unique)
foreach ($name in $schemaFiles) {
    $path = Join-Path $schemaRoot $name
    $schema = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if ($schema.'$schema' -ne 'https://json-schema.org/draft/2020-12/schema') {
        throw "Draft 2020-12 ausente en $name"
    }
}

$markdown = Get-Content -LiteralPath $examplesPath -Raw
$blocks = [regex]::Matches($markdown, '(?ms)^## .+?\r?\n\r?\n```json\r?\n(?<json>.*?)\r?\n```')
if ($blocks.Count -ne 6) { throw "Se esperaban seis ejemplos locales M1; hay $($blocks.Count)" }
$samples = @{}
foreach ($block in $blocks) {
    $envelope = $block.Groups['json'].Value | ConvertFrom-Json -AsHashtable
    if ($envelope.eventVersion -ne '1.0') { throw "Versión inesperada en $($envelope.eventType)" }
    if (-not $schemaByEvent.ContainsKey($envelope.eventType)) { throw "Evento no cubierto: $($envelope.eventType)" }
    if (-not (Test-Data $envelope.eventType $envelope.data)) { throw "El ejemplo Jackson no valida: $($envelope.eventType)/$($envelope.data.status)" }
    $samples["$($envelope.eventType):$($envelope.data.status)"] = $envelope.data
}
if ($samples.Count -ne 6) { throw "Se esperaban seis variantes válidas; hay $($samples.Count)" }

Assert-Absent 'updateExemptionStatus APPROVED' $samples['updateExemptionStatus:APPROVED'] @('reason')
Assert-Absent 'updateExemptionStatus REJECTED' $samples['updateExemptionStatus:REJECTED'] @('exemptionId', 'percentage', 'validFrom', 'validUntil')
Assert-Absent 'updatePaymentPlanStatus GRANTED' $samples['updatePaymentPlanStatus:GRANTED'] @('reason')
Assert-Absent 'updatePaymentPlanStatus REJECTED' $samples['updatePaymentPlanStatus:REJECTED'] @('planId', 'installments', 'totalAmount')
$omittedUntil = Copy-Data $samples['updateExemptionStatus:APPROVED']
$null = $omittedUntil.Remove('validUntil')
if (-not (Test-Data 'updateExemptionStatus' $omittedUntil)) { throw 'APPROVED válido debe permitir omitir validUntil' }
$explicitNull = Copy-Data $samples['updateExemptionStatus:APPROVED']
$explicitNull.validUntil = $null
Assert-Invalid 'validUntil nulo debe omitirse' 'updateExemptionStatus' $explicitNull

$additionalPropertyCases = @(
    @{ EventType = 'exemptionRequested'; Sample = 'exemptionRequested:' },
    @{ EventType = 'updateExemptionStatus'; Sample = 'updateExemptionStatus:APPROVED' },
    @{ EventType = 'paymentPlanRequested'; Sample = 'paymentPlanRequested:' },
    @{ EventType = 'updatePaymentPlanStatus'; Sample = 'updatePaymentPlanStatus:GRANTED' }
)
foreach ($case in $additionalPropertyCases) {
    $bad = Copy-Data $samples[$case.Sample]
    $bad.unexpectedProperty = 'no pertenece al contrato'
    Assert-Invalid "propiedad adicional en $($case.EventType)" $case.EventType $bad
}

$bad = Copy-Data $samples['exemptionRequested:']
$bad.requestId = 'no-es-uuid'
Assert-Invalid 'UUID de solicitud inválido' 'exemptionRequested' $bad
$bad = Copy-Data $samples['exemptionRequested:']
$bad.requestedPercentage = 0
Assert-Invalid 'porcentaje cero' 'exemptionRequested' $bad
$bad = Copy-Data $samples['exemptionRequested:']
$bad.requestedPercentage = 101
Assert-Invalid 'porcentaje mayor a 100' 'exemptionRequested' $bad
$bad = Copy-Data $samples['exemptionRequested:']
$bad.requestedPercentage = -1
Assert-Invalid 'porcentaje negativo' 'exemptionRequested' $bad
$bad = Copy-Data $samples['exemptionRequested:']
$bad.requestedFrom = '31/12/2026'
Assert-Invalid 'fecha fuera de formato ISO' 'exemptionRequested' $bad
$bad = Copy-Data $samples['exemptionRequested:']
$bad.requestedFrom = '2026-02-30'
Assert-Invalid 'fecha calendario inexistente' 'exemptionRequested' $bad
$bad = Copy-Data $samples['exemptionRequested:']
$bad.requestedFrom = '2024-02-29'
if (-not (Test-Data 'exemptionRequested' $bad)) { throw 'La fecha bisiesta válida debe aceptarse' }
$bad = Copy-Data $samples['updateExemptionStatus:APPROVED']
$bad.status = 'PENDING'
Assert-Invalid 'estado de exención desconocido' 'updateExemptionStatus' $bad
$bad = Copy-Data $samples['updateExemptionStatus:APPROVED']
$bad.reason = 'No corresponde'
Assert-Invalid 'reason exclusivo de REJECTED en APPROVED' 'updateExemptionStatus' $bad
$bad = Copy-Data $samples['updateExemptionStatus:REJECTED']
$bad.exemptionId = '7330b69b-1557-4853-bdc3-d0b63e8f6f9e'
Assert-Invalid 'exemptionId exclusivo de APPROVED en REJECTED' 'updateExemptionStatus' $bad
$bad = Copy-Data $samples['updateExemptionStatus:APPROVED']
$bad.percentage = 101
Assert-Invalid 'porcentaje aprobado mayor a 100' 'updateExemptionStatus' $bad

$bad = Copy-Data $samples['paymentPlanRequested:']
$bad.debtIds = @()
Assert-Invalid 'lista de deudas vacía' 'paymentPlanRequested' $bad
$bad = Copy-Data $samples['paymentPlanRequested:']
$bad.debtIds = @('no-es-uuid')
Assert-Invalid 'UUID de deuda inválido' 'paymentPlanRequested' $bad
$bad = Copy-Data $samples['paymentPlanRequested:']
$bad.installments = 0
Assert-Invalid 'cantidad de cuotas no positiva' 'paymentPlanRequested' $bad
$bad = Copy-Data $samples['paymentPlanRequested:']
$bad.totalDebt = 0
Assert-Invalid 'importe total de deuda no positivo' 'paymentPlanRequested' $bad

$bad = Copy-Data $samples['updatePaymentPlanStatus:GRANTED']
$bad.status = 'ACTIVE'
Assert-Invalid 'estado de plan desconocido' 'updatePaymentPlanStatus' $bad
$bad = Copy-Data $samples['updatePaymentPlanStatus:GRANTED']
$bad.reason = 'No corresponde'
Assert-Invalid 'reason exclusivo de REJECTED en GRANTED' 'updatePaymentPlanStatus' $bad
$bad = Copy-Data $samples['updatePaymentPlanStatus:REJECTED']
$bad.planId = '5e1d791a-c146-4cfa-a9c6-0e7cad559015'
Assert-Invalid 'planId exclusivo de GRANTED en REJECTED' 'updatePaymentPlanStatus' $bad
$bad = Copy-Data $samples['updatePaymentPlanStatus:GRANTED']
$bad.totalAmount = 0
Assert-Invalid 'importe total concedido no positivo' 'updatePaymentPlanStatus' $bad

Write-Output "OK: 4 schemas Draft 2020-12; 6 ejemplos Jackson; $negativeCases negativos de contrato; variantes, omisiones/nulos y propiedades adicionales comprobados."
