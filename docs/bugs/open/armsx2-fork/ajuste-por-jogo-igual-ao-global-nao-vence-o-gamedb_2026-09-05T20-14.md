# Bug: ajuste por jogo com valor igual ao global é descartado em silêncio e o GameDB vence

- **Detectado em:** 2026-09-05 20:14 (achado ao investigar o relato de NFS Underground)
- **Origem:** **delta do fork** — `writeGameSettingsIni` é nosso; o mecanismo de pin é do core
- **Errors (serviço):** nenhum — não é crash
- **Classe:** fail (ajuste do usuário não tem efeito, sem aviso)
- **Reincidência:** não
- **Feature:** nenhuma
- **Tasks que o resolvem:** nenhuma ainda
- **Relacionado:** [NFS Underground 1 e 2 nascem com "No Readbacks" forçado](nfs-underground-no-readbacks-forcado-pelo-overlay_2026-09-05T20-14.md)
  — o primeiro caso concreto encontrado

> ⚠️ **A medição em aparelho (fim do arquivo) ampliou este defeito.** O título fala do valor igual
> ao global; medido, o caso comum é pior: **um ajuste por jogo feito pela biblioteca não gera
> camada de jogo nenhuma**, então nada é pinado para valor algum. O caminho "só o que difere do
> global" é o do menu **em jogo**.

## Sintoma

O usuário abre as configurações **de um jogo**, escolhe um valor que o GameDB contradiz, e o
ajuste **não tem efeito**: no boot seguinte o banco reaplica o valor dele. Não há aviso, não há
erro, e a UI continua exibindo a escolha do usuário.

Acontece exatamente quando **o valor escolhido coincide com o valor global**. Se o usuário
escolher qualquer outro valor, funciona.

## Por que — a cadeia, verificada elo a elo

1. **O que vence o GameDB é a presença da chave na camada de jogo, não o valor dela.**
   `ComputePerGameOverrides` percorre `s_gs_keys` e marca o *pin* com
   `game_layer.ContainsValue("EmuCore/GS", row.key)`
   ([PerGameOverrides.cpp:228-240](../../../../pcsx2/PerGameOverrides.cpp#L228)). A camada de jogo
   é o arquivo `gamesettings/<serial>_<CRC>.ini`, lido em
   [VMManager.cpp:1148](../../../../pcsx2/VMManager.cpp#L1148).

2. **O pin é consultado antes de aplicar a correção do banco**, e funciona:
   `if (pinned || (isUserHackHWFix(id) && !apply_auto_fixes)) { … continue; }`
   ([GameDatabase.cpp:872](../../../../pcsx2/GameDatabase.cpp#L872)).

3. **Mas nós gravamos a camada de jogo de forma esparsa, por diferença contra o global.**
   `writeGameSettingsIni` captura um baseline com os valores globais e depois só emite as chaves
   cujo valor difere
   ([Settings.kt:1397-1430](../../../../platforms/android/app/src/main/java/com/armsx2/config/Settings.kt#L1397)):

   ```kotlin
   emitSink = { section, key, _, value ->
       if (baseline["$section$key"] != value || …)
           NativeApp.gameIniPut(section, key, value)
   }
   ```

4. **Logo:** valor por jogo == valor global ⇒ chave não gravada ⇒ `ContainsValue` falso ⇒ sem pin ⇒
   **o GameDB aplica o valor dele por cima**.

## Isto já era conhecido — para uma chave só

O próprio `writeGameSettingsIni` tem o contorno, e o comentário nomeia a regra com precisão:

```kotlin
// O que outranks o GameDB é key presence na camada de jogo
// (ComputePerGameOverrides), so VU1's group is written even where its values match global's.
val forcedKeys: Set<String> = if (vu1ClampMode != global.vu1ClampMode)
    setOf("vu1Overflow", "vu1ExtraOverflow", "vu1SignOverflow", "vu1ExactMode")
else emptySet()
```

Ou seja: o defeito foi diagnosticado corretamente uma vez, e corrigido **apenas para
`vu1ClampMode`**. Toda outra chave que o GameDB disputa continua com o problema.

## Alcance

Vale para **qualquer** chave que apareça nas tabelas de disputa do
[`PerGameOverrides.cpp`](../../../../pcsx2/PerGameOverrides.cpp) — as 25 linhas de `UserHacks_*`
mais as que "o banco contende mas nunca foram user hack": `hw_mipmap`, `HWAccurateAlphaTest`,
`pcrtc_offsets`, `pcrtc_overscan`, `CoalesceRenderPasses`, `TriFilter`, `UserHacks_SkipDraw_*`,
`texture_preloading`, `deinterlace_mode`, `HWDownloadMode` — além dos grupos de `speedhacks`,
`gamefixes` e `clampModes`.

O caso fica **mais provável** justamente onde mais dói: quando o valor correto para desfazer uma
correção do banco é o valor **default** (e o global normalmente é o default). É o que acontece em
[NFS Underground](nfs-underground-no-readbacks-forcado-pelo-overlay_2026-09-05T20-14.md), em que a
escolha "Accurate" (0) é indistinguível de "não tocado".

## O que ainda não foi verificado

- ~~Nada foi medido em aparelho.~~ **Medido em 2026-09-05** — ver a seção no fim, que confirma o
  mecanismo e amplia o defeito.
- **Se a UI reflete a escolha depois do boot.** Se ela reexibe o valor do usuário enquanto o core
  usa o do banco, o defeito também é de diagnóstico, não só de efeito. Não conferido.

## Correção provável

A forma já usada para o VU1 generaliza: quando o jogo tem entrada no GameDB para uma chave, forçar
a gravação dessa chave na camada de jogo **mesmo quando o valor coincide com o global**. O core já
expõe o necessário para saber quais chaves o banco disputa (`s_gs_keys`, e o
`applyGSHardwareFixes` em `ApplyMode::Hypothetical`, que é justamente o modo silencioso).

Alternativa mais simples e mais grosseira: gravar a camada de jogo **completa** em vez de esparsa.
Custa a propriedade que o comentário do código diz querer preservar — "um ajuste global posterior
ainda alcança o jogo nas chaves que ele não sobrescreveu" —, então provavelmente não é o caminho.

Qualquer das duas precisa de task própria e de uma prova em aparelho: escolher por jogo um valor
igual ao global numa chave que o banco disputa, reiniciar, e confirmar no log que o banco foi
pulado (`GameDB: Skipping GS Hardware Fix: …`).

---

## Medição em aparelho — 2026-09-05 21:33 a 21:40

Aparelho `moto g86 5G`, Android 16, APK versionCode 2004. Jogo *Need for Speed - Underground 2
(USA)*, **`SLUS-21065` · CRC `F5C7B45F`** (serial e CRC lidos da própria folha do app).

**O defeito é maior do que o descrito acima**, e o eixo "valor igual ao global" é só um dos casos.

### O que foi medido

| passo | ação | resultado |
|---|---|---|
| 1 | `gamesettings/` no estado inicial | **vazio** |
| 2 | Biblioteca → segurar a capa → **Configurações** (escopo *Jogo*) → Renderizador → Trilinear `Off` → `Forced` (valor **diferente** do global) | `armsx2-settings.json` passa a ter `"games":{"SLUS-21065":{"triFilter":2}}`; **`gamesettings/` continua vazio** |
| 3 | Bootar o jogo por intent e esperar 30 s | **`gamesettings/` continua vazio** |
| 4 | Em jogo → pausa → Renderizador → alternar *Mostrar Overscan* | **`gamesettings/SLUS-21065_F5C7B45F.ini` aparece** |

### O que isso quer dizer

**Um ajuste por jogo feito pela biblioteca nunca produz a camada de jogo.** Ele é guardado no JSON
do app e resolvido em Kotlin (`ConfigStore.resolveForGame`), que escreve na camada **base** do core
— então o *valor* chega. O que não acontece é o **pin**: `ComputePerGameOverrides` lê
exclusivamente `gamesettings/<serial>_<CRC>.ini`
([VMManager.cpp:885](../../../../pcsx2/VMManager.cpp#L885)), e o arquivo não existe. Com
`overrides` vazio, **toda** entrada do GameDB vence **toda** escolha por jogo feita pela
biblioteca, para qualquer valor — não só quando ele coincide com o global.

### A causa exata — e não é uma chamada faltando

`InGameOverlay.saveSettings` **tem** o ramo sem VM, e ele chama a coisa certa
([InGameOverlay.kt:202-212](../../../../platforms/android/app/src/main/java/com/armsx2/ui/InGameOverlay.kt#L202)):

```kotlin
if (MainActivityRuntime.eState.value == EmuState.STOPPED) {
    ConfigStore.resolveForGame(serial).writeGameSettingsIni(ConfigStore.loadGlobal(), serial)
}
```

O que falha é um degrau abaixo. `writeGameSettingsIni` começa com
`val began = NativeApp.gameIniBeginWriteForSerial(serial); if (!began) return`, e essa função
**só reescreve um arquivo que já existe**
([native-lib.cpp:4685-4693](../../../../platforms/android/app/src/main/cpp/native-lib.cpp#L4685)):

```cpp
FileSystem::FindFiles(EmuFolders::GameSettings.c_str(),
    fmt::format("{}_*.ini", Path::SanitizeFileName(serial)).c_str(), …, &results);
if (results.empty())
    return JNI_FALSE;          // <- não cria; desiste
```

Ela não pode criar porque o nome do arquivo exige o **CRC**, e sem VM ela não tem de onde tirá-lo —
por isso faz *glob* por `<serial>_*.ini`. O comentário do chamador já dizia "*a no-op when the game
never wrote an INI*"; o que ninguém escreveu é a consequência: **o único jeito de o arquivo passar
a existir é editar as configurações com o jogo rodando**, porque só aí `gameIniBeginWrite()`
(sem serial) tem CRC e cria. Um jogo cujas configurações só foram tocadas pela biblioteca nunca
ganha camada de jogo, logo nunca ganha pin.

Isso bate exatamente com os passos 2, 3 e 4 medidos acima.

### A correção é de um lado só

O CRC **está** ao alcance do Kotlin sem VM: a própria folha do jogo o mostra, via
`DiscIdentity.resolve(game.uri, game.serial)`
([HomeScreen.kt:777](../../../../platforms/android/app/src/main/java/com/armsx2/ui/home/HomeScreen.kt#L777)).
Basta o ramo sem VM resolver o CRC e garantir a existência de `gamesettings/<serial>_<CRC>.ini`
antes de chamar `writeGameSettingsIni` — o glob existente passa a encontrá-lo.

Ou seja: **não precisa mexer no JNI nem recompilar o nativo** (~14 min), só Kotlin. Isso muda o
custo da correção e deve pesar na priorização.

O caminho que grava é o **menu em jogo** (`InGameOverlay`), e é lá que a regra "só o que difere do
global" descrita acima se aplica. Ou seja, os dois defeitos se empilham:

1. pela **biblioteca**: nenhuma chave é gravada, logo nada é pinado — medido;
2. pelo **menu em jogo**: só as chaves que diferem do global são gravadas, logo o valor que
   coincide com o global não pina — verificado no código, ainda não medido isoladamente.

### Detalhe operacional que atrapalha diagnóstico

O core grava `PCSX2-Android.ini` e `gamesettings/*.ini` com permissão **`0600`**, enquanto os
demais arquivos do data root ficam `0660`. Como o pacote não é `debuggable` (o build de medição da
[TASK-0088](../../../task/TASK-0088-medir-sem-o-interpretador-do-art.md)), `run-as` não existe e o
`adb` **não consegue ler** o conteúdo desses dois arquivos — só a existência, o tamanho e a data.
Dá para provar que o INI foi criado, não o que ele contém. Vale considerar `0640` para eles, ou uma
ação de exportação no app.

### O que ainda falta medir

- Confirmar o caso 2 isoladamente: pelo menu em jogo, pôr uma chave disputada pelo banco no mesmo
  valor do global e checar que ela **não** entra no INI.
- Confirmar o efeito no core, e não só a presença do arquivo: com o INI gravado, ver a linha
  `GameDB: Skipping GS Hardware Fix: …`. Hoje isso **não é observável** — o console do core sai em
  `logcat` sob a tag `STDOUT` e para logo depois do dump de `EmuFolders`; não há `emulog.txt` em
  `logs/`. Tornar esse log alcançável é pré-requisito para fechar este relatório.
