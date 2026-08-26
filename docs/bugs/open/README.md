# Bugs Abertos (Triagem de Telemetria)

> Rastreabilidade: todo bug corrigido deve citar as tasks que o resolveram, e a task deve citar o
> bug de volta. Ver [`docs/README.md`](../../README.md).

Pasta onde novos bugs encontrados na telemetria de produção (`/logErr`, projeto `armsx2/*`) são gravados.

> **Status atual:** Há 12 investigações nesta pasta — 9 do aplicativo e 3 do próprio processo de
> rastreabilidade ([FEAT-0002](../../features/FEAT-0002-rastreabilidade-verificavel.md)). A produção
> está na versão **1.0.23** (versionCode 37, publicada em 2026-08-25), que leva a correção
> candidata da tela branca e aguarda confirmação em campo. Bugs validados ficam em
> [`docs/bugs/done/`](../done/README.md).
>
> **Validação local da rodada:** `assembleUnrestrictedDebug` e
> `testUnrestrictedDebugUnitTest` concluídos com sucesso (15 testes, 0 falhas).
>
> **⚠️ Os três bugs gráficos de Mali (tela preta A07, tela vermelha A15, falso positivo do monitor
> visual) não devem receber nova correção pontual.** A análise consolidada e o plano de saída estão
> em [`docs/plano-grafico-mali-convergencia-upstream.md`](../../plano-grafico-mali-convergencia-upstream.md).

## Convenção de nome

`<componente>-<sintoma-curto>_<timestamp-ISO>.md` — ex.: `native-jit-sigsegv-sotc_2026-07-06T14-30.md`

## Template

```markdown
# Bug: <título descritivo>

- **Detectado em:** YYYY-MM-DD HH:MM (telemetria de produção)
- **Origem:** telemetria `<project>` (`<file>::<method>`)
- **Errors (serviço):** <IDs> (N ocorrências)
- **Classe:** crash / fail / inconclusive
- **Reincidência:** primeira vez / N execuções / recorrência de [[bug-existente]]
- **Feature:** [FEAT-NNNN](../../features/FEAT-NNNN-<slug>.md) — ou `nenhuma`
- **Tasks que o resolvem:** [TASK-NNNN](../../task/TASK-NNNN-<slug>.md) — preencher ao corrigir

## Sintoma
<exit code, processo, tag de log, trecho-chave RESUMIDO do log>

## Causa raiz
<confirmada no código: arquivo:linha do ponto de falha real>

## Como reproduzir
<comando direto / passos>

## Próximos passos
<o que falta investigar/corrigir>
```
