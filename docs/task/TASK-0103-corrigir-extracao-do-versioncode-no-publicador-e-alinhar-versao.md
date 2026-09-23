# TASK-0103: corrigir extração do versionCode no publicador e alinhar versão do fork

- **Status:** concluída
- **Criada em:** 2026-09-22
- **Concluída em:** 2026-09-22
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0103:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Corrigir a extração de `versionCode` na função `Invoke-VersionBump` em `publish-retrosystem-ps2.ps1` e
alinhar a versão padrão em `platforms/android/gradle.properties` com a versão 2.0.5 (code 2005)
publicada na trilha `rgs/ps2fork/`.

## Contexto

A função `Invoke-VersionBump` em `publish-retrosystem-ps2.ps1` usava:
```powershell
$codeNums = [regex]::Matches($codeLine, '\d+')
if ($codeNums.Count -ne 1) {
    throw "A linha do versionCode tem $($codeNums.Count) numeros; nao da para incrementar com seguranca:`n  $codeLine"
}
```
Caso a linha ou o bloco contivesse dígitos adicionais ou formatação específica, a contagem de números
excedia 1 e abortava o bump de versão com exceção.

A extração foi refatorada para casar expressamente o valor numérico após `=`:
```powershell
$codeMatch = [regex]::Match($codeLine, '=\s*(\d+)\s*$')
if (-not $codeMatch.Success) {
    throw "Nao encontrei o valor numerico do versionCode:`n  $codeLine"
}
$oldCode = [int]$codeMatch.Groups[1].Value
```

Adicionalmente, `platforms/android/gradle.properties` tem seus defaults atualizados para
`versionCode=2005` e `versionName=2.0.5`, refletindo a versão corrente do canal de release do fork.

## Escopo

**Entra:**
- Correção da regex de extração numérica em `publish-retrosystem-ps2.ps1`.
- Atualização de `armsx2.versionCode` e `armsx2.versionName` em `platforms/android/gradle.properties`.

**Não entra:**
- Alterações em lógica de upload S3/R2 ou purga de Cloudflare.
- Alterações em código do emulador (`app/src`, `pcsx2/`, `common/`).

## Como validar

1. Verificar que o validador `python scripts/check_traceability.py` aprova o commit e a rastreabilidade.
2. Execução de `publish-retrosystem-ps2.ps1 -DryRun` para verificar parsing de versão sem erros.
