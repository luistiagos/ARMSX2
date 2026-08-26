# Bug: Configurações que não fazem nada — `HardwareReadbacks` morto e Auto Flush sobrescrito pelo GameDB

- **Detectado em:** 2026-08-10 16:02 (auditoria de código, durante investigação de performance)
- **Origem:** auditoria de `ApplyAndroidPerformanceDefaults` + `SettingsActivity` + `GameDatabase::applyGSHardwareFixes`
- **Errors (serviço):** nenhum — não é crash, não gera telemetria.
- **Classe:** fail (UX / configuração enganosa)
- **Reincidência:** sistêmico
- **Status:** corrigido em 2026-08-10, aguardando retest em dispositivo

## Sintoma

Usuário mexe em opções de desempenho e nada muda. Pior: como a mudança é silenciosa, ele conclui
que "já testou tudo" e o relato de performance que chega até nós fica contaminado — não dá para
confiar em "testei com Auto Flush desligado".

Três problemas distintos, todos com o mesmo efeito.

## Causa raiz (CONFIRMADA no código)

### 1. `HardwareReadbacks` — setting escrito e nunca lido

`ApplyAndroidPerformanceDefaults()` gravava a chave, a migração também, **e a UI expunha um switch
para ela** (`sw_hw_readbacks` em Configurações → Desempenho, mais o switch do diálogo in-game):

```cpp
settings.SetBoolValue("EmuCore/GS", "HardwareReadbacks", false);
```

Não existia leitura, não existia campo no `Pcsx2Config`, não existia nada. Chave morta que só
poluía o INI. O controle real equivalente é `HWDownloadMode`
([Config.h:392-398](../../../app/src/main/cpp/pcsx2/Config.h#L392-L398), enum `GSHardwareDownloadMode`),
que já estava exposto logo abaixo do switch morto — como um slider de 0 a 3 sem rótulo nenhum.
Dois controles para a mesma coisa, um deles mentindo.

### 2. Auto Flush da UI é sobrescrito pelo GameDB

O spinner escreve `UserHacks_AutoFlushLevel`, mas como `ManualUserHacks` está `false` — o que é o
comportamento **correto**, não mexer nisso — o GameDB reaplica o valor dele por cima
([GameDatabase.cpp:694](../../../app/src/main/cpp/pcsx2/GameDatabase.cpp#L694)):

```cpp
// Only apply GS HW fixes if the user hasn't manually enabled HW fixes.
const bool apply_auto_fixes = !config.ManualUserHacks;
```

Em qualquer jogo com `autoFlush` no GameDB — God of War 2 incluído
([GameIndex.yaml:12385](../../../app/src/main/assets/resources/GameIndex.yaml#L12385)) — o spinner é
no-op silencioso. Justamente nos jogos onde o usuário mais tentaria usá-lo.

### 3. `renderHalfpixeloffset` — JNI morto

`NativeApp.renderHalfpixeloffset(int)` estava declarado e implementado, mas **nenhum código Java o
chamava** — não havia controle correspondente em nenhum layout. E se houvesse, cairia no mesmo
problema do item 2: o GameDB força `halfPixelOffset: 5` no GoW2.

## Como reproduzir

1. Abrir Configurações → mudar o Auto Flush (HW) para outro valor.
2. Rodar God of War 2 e conferir no log o aviso do GameDB reaplicando o fix.
3. Medir FPS: idêntico em qualquer posição do spinner.

## Correção aplicada (2026-08-10)

### 1. `HardwareReadbacks` eliminado, `HWDownloadMode` virou o controle único

- Removidas as duas escritas em [main.cpp](../../../app/src/main/cpp/main.cpp)
  (`ApplyAndroidPerformanceDefaults` e a migração de perfil), a escrita em
  `applyPerformancePresetValues` e o parâmetro `hardwareReadbacks` que ela carregava.
- Novo `RemoveDeadSettingKeys()` em main.cpp apaga a chave dos INIs já existentes. Roda **fora** do
  gate de `AndroidPerformanceProfileVersion` de propósito: subir a versão do perfil re-executaria a
  migração inteira e sobrescreveria valores que o usuário escolheu de propósito.
- O switch morto saiu do card de Desempenho (e dos dois layouts legados, não inflados, que também o
  declaravam). O slider cru de 0–3 virou o spinner `sp_hw_download_mode` com os quatro modos
  nomeados (`R.array.hw_download_mode`, na ordem de `GSHardwareDownloadMode`).
- O switch do diálogo in-game (`switch_hw_readbacks` em `MainActivity`) passou a escrever
  `HWDownloadMode` de verdade: ligado = 0 (Accurate), desligado = o modo reduzido que já estava
  configurado (default 1), para não rebaixar quem escolheu Unsynchronized nas Configurações. O
  estado inicial agora vem do setting em vez de ser `true` fixo.

### 2. Auto Flush passa a admitir quando não manda em nada

Novo JNI `NativeApp.getGameDbAutoFlushLevel()` devolve o valor que o GameDB força no jogo em
execução, ou -1 (sem VM, serial desconhecido, ou jogo sem `autoFlush`). Em `SettingsActivity`:

- valor >= 0 → o spinner mostra o valor forçado, fica desabilitado e a legenda vira
  "Definido automaticamente para este jogo pelo banco de dados — este controle não tem efeito aqui";
- caso contrário → funciona normal, com a legenda fixa avisando que jogos com correção própria no
  GameDB ignoram o valor.

`ManualUserHacks` continua `false` de propósito — ligar isso descartaria **todos** os fixes
automáticos do GameDB e pioraria mais do que ajudaria.

### 3. `renderHalfpixeloffset` removido

JNI apagado dos dois lados (declaração em `NativeApp.java`, implementação em `main.cpp`). Não havia
controle, e se houvesse cairia no problema do item 2.

### 4. Auditoria dos demais controles contra `isUserHackHWFix`

Levantados todos os `setSetting("EmuCore/GS", ...)` de `SettingsActivity` e cruzados com
[GameDatabase.cpp:419-439](../../../app/src/main/cpp/pcsx2/GameDatabase.cpp#L419-L439).
**`UserHacks_AutoFlushLevel` era o único** afetado pelo override duro do item 2.

Os outros que aparecem em `gsHWFixes` (`hw_mipmap`, `texture_preloading`, `TriFilter`,
`accurate_blending_unit`, `deinterlace_mode`, `pcrtc_offsets`, `pcrtc_overscan`) **não** são
user-hack fixes: o GameDB os trata com guardas próprias (só aplica se estiver em `Automatic`, ou
faz `min`/`max` com o valor do usuário). Comportamento upstream, intencional — nada a fazer.

## Confirmação no upstream (auditoria 2026-08-10)

O item 2 está reportado no `ARMSX2/ARMSX2` e **continua aberto sem tratamento**:
[#399](https://github.com/ARMSX2/ARMSX2/issues/399) — *"Manual GS hardware renderer fixes are
enabled, automatic fixes were not applied"*, aberto 2026-07-23, **zero comentários**. O usuário
descreve o mecanismo exato: `GameDB Fixes` ligado, `Manual Hardware Fixes` desligado, e ainda assim
o aviso de que os fixes automáticos não foram aplicados.

Confirma que o item 2 não era teoria de leitura de código — é confusão real de usuário, e a nossa
correção chega antes da deles. Duas diferenças de abordagem:

1. **Lá o usuário vê um popup; aqui a sobreposição era silenciosa.** O aviso vem de
   `applyGSHardwareFixes` ([GameDatabase.cpp:697](../../../app/src/main/cpp/pcsx2/GameDatabase.cpp#L697)),
   que monta a string `disabled_fixes` com tudo que foi descartado. Desabilitar o controle com
   legenda (o que fizemos via `getGameDbAutoFlushLevel()`) é melhor que um popup no boot — o popup
   do #399 aparece justamente quando *nada* deveria ter sido descartado, e confunde mais que ajuda.
2. **Lá existe um toggle explícito "Fixes → Manual Hardware Fixes"** (`FixesTab.kt`), que não
   expomos. Fica como decisão em aberto: expor `ManualUserHacks` deixa o comportamento
   inspecionável, mas ligá-lo descarta **todos** os fixes automáticos do GameDB de uma vez, o que
   costuma piorar mais que ajudar. O #399 é evidência de que expor sem explicar gera bug report.

Nada a mudar no fix atual — registro para justificar a escolha de UX caso alguém compare com o
upstream depois.

## Retest (falta fazer)

Não foi possível compilar o APK nesta sessão: a máquina está sem RAM/paginação (C: com 861 MB e E:
com 3,1 GB livres) e o daemon do Gradle morre com `Out of Memory Error` antes de começar. O que
**foi** validado: `main.cpp` compila (ninja, alvo `main.cpp.o`), o símbolo
`Java_kr_co_iefriends_pcsx2_NativeApp_getGameDbAutoFlushLevel` está exportado no objeto,
`renderHalfpixeloffset` sumiu, o Java passa no parse do javac e nenhuma referência a recurso
removido sobrou.

Depois de liberar espaço e buildar:

1. Configurações → Desempenho: o spinner "Modo de Download por Hardware (readbacks)" mostra os
   quatro modos nomeados e não há mais switch "Readbacks por Hardware".
2. Trocar o modo e reabrir as Configurações: o valor persiste (`HWDownloadMode` no
   `PCSX2-Android.ini`), e a chave `HardwareReadbacks` não existe mais no INI.
3. Abrir as Configurações **com o GoW2 rodando** → o spinner de Auto Flush (HW) aparece desabilitado
   com a legenda do GameDB. Com o jogo parado (ou num jogo sem `autoFlush`), continua editável.
4. Diálogo in-game: o switch de readbacks reflete o modo atual e mudá-lo altera `HWDownloadMode`.

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.
