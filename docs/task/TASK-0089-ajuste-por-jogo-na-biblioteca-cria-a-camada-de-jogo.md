# TASK-0089: ajuste por jogo feito na biblioteca cria a camada de jogo

- **Status:** em andamento
- **Criada em:** 2026-09-06
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [ajuste por jogo não chega à camada lida pelo core](../bugs/open/armsx2-fork/ajuste-por-jogo-igual-ao-global-nao-vence-o-gamedb_2026-09-05T20-14.md)
  — resolve **a metade medida** do relatório; ver *O que deliberadamente não entra*
- **Commit:** — (o vínculo é o prefixo `TASK-0089:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## De onde vem

Medido no `moto g86 5G` em 2026-09-05, com *NFS Underground 2 (USA)*, `SLUS-21065` · CRC
`F5C7B45F`:

| passo | resultado |
|---|---|
| Biblioteca → segurar a capa → Configurações (escopo *Jogo*) → mudar Trilinear | grava em `armsx2-settings.json`; `gamesettings/` **fica vazio** |
| bootar o jogo | `gamesettings/` **continua vazio** |
| menu **em jogo** → mudar qualquer ajuste | `gamesettings/SLUS-21065_F5C7B45F.ini` **aparece** |

O valor do ajuste chega ao core (o Kotlin resolve por jogo e escreve na camada **base**). O que não
chega é o **pin**: `ComputePerGameOverrides` lê exclusivamente `gamesettings/<serial>_<CRC>.ini`
([VMManager.cpp:885](../../pcsx2/VMManager.cpp#L885)), e sem esse arquivo o GameDB vence toda
escolha por jogo.

## A causa, e por que não é uma chamada faltando

`InGameOverlay.saveSettings` **tem** o ramo sem VM e chama a função certa
([InGameOverlay.kt:202-212](../../platforms/android/app/src/main/java/com/armsx2/ui/InGameOverlay.kt#L202)).
Quem desiste é um degrau abaixo: `writeGameSettingsIni` começa em
`if (!NativeApp.gameIniBeginWriteForSerial(serial)) return`, e essa função faz *glob* de
`<serial>_*.ini` e **retorna `JNI_FALSE` quando não acha nada**
([native-lib.cpp:4685-4693](../../platforms/android/app/src/main/cpp/native-lib.cpp#L4685)):

```cpp
if (results.empty())
    return JNI_FALSE;          // não cria; desiste
```

Ela não pode criar porque o nome exige o **CRC**, e sem VM ela não tem de onde tirá-lo — daí o glob.
Consequência que ninguém tinha escrito: **o arquivo só nasce editando com o jogo rodando**, porque
só aí `gameIniBeginWrite()` (sem serial) tem CRC via `VMManager`. Um jogo cujas configurações só
foram tocadas pela biblioteca nunca ganha camada de jogo, logo nunca ganha pin.

## O desenho

O CRC **está** ao alcance do Kotlin sem VM: `DiscIdentity.resolve(uri, serial)`, que a folha do jogo
já usa ([HomeScreen.kt:777](../../platforms/android/app/src/main/java/com/armsx2/ui/home/HomeScreen.kt#L777)).
Então:

1. **Resolver o CRC fora da thread da UI, uma vez, ao abrir as configurações do jogo.**
   `DiscIdentity` diz na própria documentação *"Blocking — never call from the main thread"* — ele lê
   1–10 MB do ELF de boot, mais num `.chd` comprimido. O job do `SettingsApplyQueue` roda **na main
   thread**, então resolver lá dentro estaria errado. `SettingsViewModel.load` tem o `GameInfo` (e a
   `uri`) e um `viewModelScope`; `DiscIdentity.resolve` é `suspend` e já faz `withContext(IO)`.
   O resultado fica em `InGameOverlay.currentCrc`, ao lado do `currentSerial` que já existe.

2. **Semear o arquivo dentro de `writeGameSettingsIni`, num ponto só.** Com `serial` e `crc`, criar
   `gamesettings/<serial>_<CRC>.ini` vazio se ele não existir, **antes** do
   `gameIniBeginWriteForSerial`. O glob então o encontra, `BeginGameIniExport` o carrega
   (`ini->Load()` já tolera arquivo inexistente) e o fluxo segue idêntico.

Pôr a semeadura dentro de `writeGameSettingsIni`, e não no chamador, faz os **dois** caminhos sem VM
serem cobertos de uma vez: o de `InGameOverlay.saveSettings` e o de
`SettingsViewModel.resetCurrentScope`, que têm exatamente o mesmo defeito.

**Não sobra lixo.** `gameIniCommitWrite` chama `RemoveEmptySections()` e, se o resultado for vazio,
**apaga o arquivo** ([native-lib.cpp:4728](../../platforms/android/app/src/main/cpp/native-lib.cpp#L4728)).
Um arquivo-semente que não recebe nenhuma chave é removido no mesmo commit.

**Sem regressão quando o CRC não resolve.** `crc == null` mantém o comportamento de hoje: o glob não
acha, `writeGameSettingsIni` retorna cedo, nada é criado.

## O que deliberadamente NÃO entra

- **A segunda metade do relatório.** `writeGameSettingsIni` grava só as chaves cujo valor **difere
  do global**. Então um ajuste por jogo cujo valor coincide com o global continua não sendo gravado,
  continua não pinando, e o GameDB continua vencendo — inclusive no caso que motivou tudo isto
  (escolher *Accurate* para o `HWDownloadMode`, que é 0 e é o global). Corrigir isso exige decidir
  **quais** chaves forçar; o `forcedKeys` de hoje resolve só o `vu1ClampMode`. É decisão própria e
  task própria.
- **Qualquer mudança no JNI ou no nativo.** Nada aqui recompila o core.
- **O glitch gráfico do NFS.** Sem relação; a causa daquele continua aberta e é do aparelho do
  cliente.
- **A permissão `0600`** dos INIs por jogo, que impede ler o conteúdo por `adb`. Anotada no
  relatório, fora daqui.

## Como será validada

No `moto g86 5G` já conectado, com *NFS Underground 2 (USA)* (`SLUS-21065`/`F5C7B45F`), partindo de
`gamesettings/` vazio:

1. Biblioteca → segurar a capa → Configurações → escopo *Jogo* → mudar um ajuste para valor
   **diferente** do global.
2. `gamesettings/SLUS-21065_F5C7B45F.ini` tem de **existir** — hoje não existe. É a diferença que a
   task produz, e é observável por `adb shell ls` sem precisar ler o conteúdo (os arquivos são
   `0600`).
3. Devolver o ajuste ao valor global e confirmar que o arquivo é **apagado** (o commit vazio), para
   provar que a semeadura não deixa lixo.
