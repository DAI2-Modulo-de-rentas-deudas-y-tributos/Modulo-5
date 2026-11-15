# Resume hallazgos [{RuleID, File, Commit, Line, Fingerprint?}] como anotaciones de GitHub Actions,
# sin el valor del secreto. Una anotación por regla (GitHub muestra hasta 10 warnings por paso),
# las huellas para .gitleaksignore (sólo gitleaks) y un total.
(group_by(.RuleID)[:10][]
  | "::warning title=\($tool) \(.[0].RuleID)::\(length) hallazgo(s): \([.[] | "\(.File):\(.Line)@\(.Commit[0:7])"] | unique | .[:20] | join(", "))"),
([.[].Fingerprint // empty] | select(length > 0) | "::notice title=\($tool) huellas::\(join(" "))"),
"::notice title=\($tool)::\(length) hallazgo(s) en el historial"
