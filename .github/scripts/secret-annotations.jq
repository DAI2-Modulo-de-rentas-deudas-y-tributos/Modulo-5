# Resume hallazgos [{RuleID, File, Commit}] como anotaciones de GitHub Actions, sin el secreto.
# Una anotación por regla (GitHub muestra hasta 10 warnings por paso) más un total.
(group_by(.RuleID)[:10][]
  | "::warning title=\($tool) \(.[0].RuleID)::\(length) hallazgo(s): \([.[] | "\(.File)@\(.Commit[0:7])"] | unique | .[:15] | join(", "))"),
"::notice title=\($tool)::\(length) hallazgo(s) en el historial"
