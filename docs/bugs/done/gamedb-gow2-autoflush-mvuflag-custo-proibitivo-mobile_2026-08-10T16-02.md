# Bug: God of War 2 — fixes do GameDB (`autoFlush`, `mvuFlag: 0`) têm custo proibitivo em GPU móvel

- **Detectado em:** 2026-08-10 16:02 (auditoria de código, motivada por relato específico de God of
  War 2 rodando extremamente lento)
- **Origem:** auditoria de `GameIndex.yaml` + `GameDatabase::applyGSHardwareFixes`
- **Errors (serviço):** nenhum — não é crash, não gera telemetria.
- **Classe:** fail (performance)
- **Reincidência:** específico de título, mas o padrão vale para qualquer jogo com `autoFlush` no
  GameDB

## Sintoma

God of War 2 roda muito abaixo do esperado mesmo em aparelhos que dão conta de outros jogos
pesados. Não é só "jogo exigente" — a queda é desproporcional em relação a títulos comparáveis.

## Causa raiz (CONFIRMADA no código)

As entradas do GoW2 no GameDB forçam um conjunto de fixes caros. NTSC-U
([GameIndex.yaml:12385](../../../app/src/main/assets/resources/GameIndex.yaml#L12385)) e PAL
([GameIndex.yaml:6370](../../../app/src/main/assets/resources/GameIndex.yaml#L6370)) são idênticas:

```yaml
SCUS-97481:
  name: "God of War II"
  region: "NTSC-U"
  compat: 5
  speedHacks:
    mvuFlag: 0          # Fixes enemies attacks turning into squares.
  gsHWFixes:
    halfPixelOffset: 5  # Fixes misaligned bloom.
    alignSprite: 1      # Fixes water vertical lines.
    autoFlush: 1        # Fixes sun occlusion.
    nativeScaling: 1    # Fixes light blooms.
```

Dois desses são especialmente caros no nosso contexto:

**`autoFlush: 1`** — o PCSX2 normalmente **acumula** primitivas e emite uma draw call só. O
autoflush detecta quando o jogo lê uma textura que é o próprio framebuffer sendo escrito, e nesse
caso quebra o lote e emite a draw call ali mesmo, para preservar a ordem de leitura. O comentário
do código explica o porquê ([GSState.cpp:3374-3378](../../../app/src/main/cpp/pcsx2/GS/GSState.cpp#L3374-L3378)):

> *"what we are checking for is draws over a texture when the source and destination are
> themselves. Because one page of the texture gets buffered in the Texture Cache (the PS2's one) if
> any of those pixels are overwritten, you still read the old data."*

O custo é **multiplicar o número de draw calls**, e cada uma delas é auto-referente (RT == textura),
exigindo barreira entre elas. Em GPU **tile-based** (todo Adreno e todo Mali) barreira dentro de um
render pass é proporcionalmente mais cara que num GPU desktop imediato — pode forçar resolve e
reload da memória de tile. É onde esse fix foi calibrado, e ninguém no upstream tinha motivo para
pesar esse custo.

> ⚠️ **Atenção à escala — é contraintuitiva.** O enum é
> ([Config.h:407-412](../../../app/src/main/cpp/pcsx2/Config.h#L407-L412)):
>
> ```cpp
> enum class GSHWAutoFlushLevel : u8 { Disabled /*0*/, SpritesOnly /*1*/, Enabled /*2*/ };
> ```
>
> O GoW2 usa `1` = **`SpritesOnly`**, que já é o **mais leve** dos dois níveis ativos. O filtro é
> real, não cosmético — só sprites passam, triângulos são ignorados
> ([GSState.cpp:3265](../../../app/src/main/cpp/pcsx2/GS/GSState.cpp#L3265)):
>
> ```cpp
> if (!PRIM->TME || (GSConfig.UserHacks_AutoFlush == GSHWAutoFlushLevel::SpritesOnly && prim != GS_SPRITE))
>     return false;
> ```
>
> Ou seja: **não existe nível intermediário a explorar.** Abaixo de `SpritesOnly` só há `Disabled`.
> A decisão é binária, e desligar traz o bug de sun occlusion de volta.

**`mvuFlag: 0`** — o campo vira `SpeedHack::MVUFlag` e é aplicado como
`config.Speedhacks.Set(MVUFlag, 0)` ([GameDatabase.cpp:551](../../../app/src/main/cpp/pcsx2/GameDatabase.cpp#L551)),
ou seja `vuFlagHack = false`. Sem o flag hack, a VU recalcula flags de status que quase nunca são
lidos, em todo frame.

É um **conflito direto com os nossos defaults**: [main.cpp:156](../../../app/src/main/cpp/main.cpp#L156)
liga `vuFlagHack = true` para todos os jogos, e o GoW2 desliga de volta no boot. Some-se a isso o
MTVU mal alocado (ver
[`main-mtvu-forcado-sem-checar-nucleos-grandes`](./main-mtvu-forcado-sem-checar-nucleos-grandes_2026-08-10T16-02.md)):
VU mais lenta, fixada num núcleo pequeno, com a EE bloqueada esperando por ela.

Ou seja, GoW2 desativa exatamente as otimizações que sustentam o desempenho dos outros títulos.
Nenhum desses valores está errado — todos são fixes de **correção** legítimos, herdados do upstream
e calibrados para PC. O problema é que não existe nenhuma noção de custo/plataforma na aplicação
deles.

Os fixes são aplicados porque `ManualUserHacks` está `false`, o que é o comportamento correto
([GameDatabase.cpp:694](../../../app/src/main/cpp/pcsx2/GameDatabase.cpp#L694)):

```cpp
const bool apply_auto_fixes = !config.ManualUserHacks;
```

## Como reproduzir

1. Rodar God of War 2 (SCUS-97481 ou SCES-54206) em qualquer aparelho móvel.
2. Comparar com um jogo de peso similar **sem** `autoFlush` no GameDB.
3. Para isolar o custo: ligar `UserHacks` (`ManualUserHacks`) para desativar os fixes automáticos e
   medir de novo — deve subir bastante, ao custo dos artefatos que os fixes corrigem.

## Próximos passos

1. **Quantificar o custo de cada fix isoladamente** antes de mexer em qualquer coisa. Medir GoW2
   com `autoFlush` on/off e `mvuFlag` 0/1, quatro combinações, mesmo trecho de gameplay. Sem esses
   números não dá para decidir nada.
2. ~~Avaliar um nível mais brando de `autoFlush`.~~ **Sem saída por aqui** — ver a caixa de atenção
   na causa raiz. O GoW2 já usa `SpritesOnly`, o nível mais leve dos ativos; a única alternativa é
   `Disabled`, que devolve o artefato. A decisão é binária.
3. Considerar um mecanismo de **override de GameDB por plataforma**: um conjunto de fixes que o
   Android pode relaxar quando o custo em GPU tile-based for desproporcional, trocando correção
   por jogabilidade. Decisão de produto, não só técnica — precisa de alinhamento antes de
   implementar.
4. Se o item 3 avançar, expor ao usuário como escolha explícita ("priorizar fidelidade
   × desempenho") em vez de decidir silenciosamente por ele.
5. **Não** editar as entradas do `GameIndex.yaml` diretamente: o arquivo é sincronizado do upstream
   e qualquer edição local seria perdida no próximo port de GameDB.

## Estado no upstream (auditoria 2026-08-10)

Varredura do org `ARMSX2` (4 branches, 100+ tags, issues abertos e fechados): **nada foi feito
sobre o custo de fixes de GameDB em GPU tile-based.** Não há override por plataforma, nem discussão
sobre `autoFlush` em mobile, nem ajuste das entradas do GoW2. Este bug é território aberto — dos
cinco levantados, é o único onde o upstream não chegou antes.

Issues de God of War lá, para contexto (nenhum é sobre este problema):

- [#378](https://github.com/ARMSX2/ARMSX2/issues/378) GoW2 "Eye of Gorgon hang" — **aberto**. É
  travamento ligado a savestate, não performance. Um comentário indica que o hitch de *salvar*
  estado é que congela os baús do Gorgon/Phoenix.
- [#513](https://github.com/ARMSX2/ARMSX2/issues/513) GoW fontes e efeitos quebrados (Chaos Blade,
  caixas de vida, sangue) em **Mali-G615 + Vulkan** — aberto, mantenedor comentou "I'm looking into
  this" em 2026-08-09. Reforça o argumento de fbfetch em
  [`gsdevicevk-allowlist-vulkan-rejeita-mali-moderno`](./gsdevicevk-allowlist-vulkan-rejeita-mali-moderno_2026-08-10T16-02.md).
- [#561](https://github.com/ARMSX2/ARMSX2/issues/561) GoW 1 e 2 crashando — fechado.
- [#464](https://github.com/ARMSX2/ARMSX2/issues/464) Feature request: presets de configuração por
  jogo. É o pedido de usuário mais próximo do item 3 acima; vale acompanhar como o upstream
  responde antes de projetar o nosso.

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.
