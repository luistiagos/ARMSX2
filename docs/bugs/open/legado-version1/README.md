# Bugs da linha antiga / version1

Esta pasta contém **7 relatórios** que não são bugs confirmados do ARMSX2-fork atual.

Eles foram preservados como histórico: seis apontam componentes/comportamentos da linha antiga e um
(SotC `0x12218`) ainda precisa ser reproduzido depois do transplante do JIT ARM64. A justificativa
individual está no [`README` da triagem](../README.md#linha-antiga--version1).

Não portar uma correção desta pasta por semelhança de sintoma. Primeiro reproduzir no fork e abrir o
caminho atual do código; se a causa for confirmada, mover o relatório para `armsx2-fork/` e atualizar
a auditoria.
