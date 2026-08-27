# TASK-0030: adotar a pasta de dados escolhida na versão anterior

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0030:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Item 8 do inventário de migração de 2026-08-27, e o §6.1 do
[plano do fork](../plano-fork-sobre-upstream.md) — o risco que precisava estar resolvido **antes de
qualquer publicação**, porque atinge quem já é usuário.

O plano o descrevia como "duas respostas concorrentes ao mesmo problema", nosso modelo contra o
deles. Ao abrir os arquivos, isso se mostrou errado em parte: `DataDirectoryManager` traz o
cabeçalho de licença do ARMSX2 — **é código deles**, do modelo antigo. Não há disputa de desenho a
resolver, só dados de usuário a não perder.

## O risco, concretamente

As duas linhas guardam a mesma decisão em lugares que não se enxergam:

```
versão anterior   SharedPreferences "armsx2"   chave "data_dir_path"
este fork         SharedPreferences "ARMSX2"   chave "systemDir"
```

Nome de SharedPreferences é nome de **arquivo**, portanto case-sensitive: são dois arquivos
distintos, e os dois sobrevivem à atualização — lado a lado, sem que um leia o outro.

Quem **não** escolheu pasta nenhuma não é afetado: as duas árvores caem no mesmo
`getExternalFilesDir(null)`. É por isso que o `PCSX2-Android.ini` sobreviveu byte a byte na
instalação por cima da 1.0.23.

Quem **escolheu** uma pasta própria atualizaria e cairia no padrão: biblioteca vazia, memory cards e
savestates aparentemente sumidos. Os dados continuam no disco, no caminho antigo; é o app que deixa
de olhar para lá. É o pior tipo de perda — silenciosa e parecida com corrupção.

## A correção

`adoptLegacyDataRoot()`, chamada no `onCreate` logo depois de `systemDir` ser lido. Três condições,
todas necessárias:

1. este fork ainda não tem `systemDir` — nunca sobrescrever uma escolha feita **aqui**;
2. a chave antiga existe e não está vazia;
3. o caminho é gravável **agora** — `validateSystemDirWritable` escreve e apaga um arquivo de prova.
   Um cartão SD removido, ou uma permissão que não sobreviveu à reinstalação, tornaria a adoção pior
   que o padrão.

Roda a cada arranque em vez de uma vez só: é uma leitura de pref, e a condição 1 já a torna
idempotente. Uma marca de "já migrei" só acrescentaria um estado a errar.

## Como validar

**O ramo negativo, verificado no Galaxy A12** (aparelho sem pasta customizada legada):

| Verificação | Resultado |
|---|---|
| Código no APK, depois do R8 | `data_dir_path` e a mensagem de log presentes em `classes.dex` |
| Biblioteca após atualizar | `Total de jogos: 12628` |
| `PCSX2-Android.ini` | intacto, 12.101 bytes |
| Log da migração | silencioso — não há chave legada, e ela sai sem tocar em nada |

> A verificação do DEX foi feita com `grep -a` no binário. A primeira tentativa usou `strings`, que
> **não achou nem uma string de controle sabidamente presente** (`systemDir`) — um falso negativo
> que teria me feito "consertar" código que estava certo. Controle antes de conclusão.

**O ramo positivo — adoção de uma pasta customizada — NÃO foi exercitado.** Este aparelho nunca teve
uma, e criar uma exige `root` (as SharedPreferences são privadas e o build é release, então
`run-as` não serve). Para verificar de verdade: instalar a 1.0.23, escolher uma pasta de dados na
tela de armazenamento, atualizar para o fork e conferir no logcat a linha
`adotando a pasta de dados da versao anterior: <caminho>`.

## Resultado

Entregue, com o ramo positivo pendente de verificação em aparelho com estado legado.
