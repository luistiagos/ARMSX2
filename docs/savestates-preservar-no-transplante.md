# Preservar os savestates no transplante — o que a medição diz

- **Data:** 2026-08-26
- **Pergunta:** o transplante sobre o upstream invalida os savestates de todo usuário instalado
  (`0x9A54` → `0x9A59`). Dá para não invalidar?
- **Resposta curta:** **dá, e a maior parte da máquina já existe no upstream.** Não é escrever um
  conversor do zero; é acrescentar uma versão a um leitor legado que eles já mantêm.

---

## 1. O que a versão realmente gateia

[`SaveState.cpp:1102`](../app/src/main/cpp/pcsx2/SaveState.cpp#L1102):

```cpp
if (savever > g_SaveVersion || (savever >> 16) != (g_SaveVersion >> 16))
    // rejeita
```

O corte é na **palavra alta**. `0x9A54` contra `0x9A59` é rejeição dura, sem faixa de
compatibilidade.

E não existe, hoje, nenhuma máquina de leitura versionada para se apoiar. `SaveStateBase` tem
`GetVersion()`, mas:

```cpp
u32 GetVersion() const { return (m_version & 0xffff); }   // SÓ os 16 bits baixos
```

Os 16 bits baixos são `0x0000` nos dois lados — a diferença inteira mora na metade que esse getter
descarta. E um `grep` por `GetVersion()` em todo o core devolve **apenas a própria declaração**:
nenhum ponto de desserialização consulta a versão. O desenho do PCSX2 sempre foi "bump e invalida".

---

## 2. Qual é a diferença real entre `0x9A54` e `0x9A59`

Medido, não suposto. O savestate é um stream binário plano: o que importa é a **ordem e o tipo** do
que é escrito. Comparando a sequência de chamadas de serialização arquivo a arquivo entre as duas
árvores:

| | |
|---|---|
| Arquivos que participam da serialização | **56** |
| Com sequência de wire **idêntica** | **54** |
| Com sequência **diferente** | **2** |

E os dois que diferem não são mudanças de formato:

- **`SIO/Sio2.cpp`** — renomeação pura. `send3`→`CmdQueue` (`std::array<u32,16>`), `recv1`→`CmdStat`
  (`u32`), `send3Read`→`queueRead` (`bool`), `send3Position`→`queuePosition` (`size_t`),
  `send3Complete`→`queueComplete` (`bool`). Mesmos tipos, mesma ordem, mesmos endereços nos
  comentários. **Bytes idênticos.**
- **`SaveState.cpp`** — `Freeze(fpuRegs)` virou `Freeze(fpu_wire)`. Eles alargaram o `FPRreg` de
  runtime de 32 para 64 bits (`double`) por precisão e introduziram um struct de wire **para o
  formato do savestate não mexer**. O comentário deles é explícito: *"The format is shared with
  upstream and does not move"*, com `static_assert(sizeof(fpuRegistersWire) == 264)`. O nosso
  `fpuRegisters` também dá **264 bytes**. **Compatível.**

Também conferidos e idênticos: `tlbs`, `cachedTlbs_t`, e o conjunto de campos do VIF (a diferença
aparente era só o header em que moram — nós dividimos entre `Vif.h` e `VifDef.h`, eles não).

### O que de fato quebra

Uma coisa só, e ela atravessa vários structs: **os contadores de ciclo foram alargados de `u32`
para `u64`.**

| Onde | Campos |
|---|---|
| `cpuRegisters` | `sCycle[32]`, `cycle`, `nextEventCycle`, `lastEventCycle`, `lastCOP0Cycle`, `lastPERFCycle[2]` |
| `psxRegisters` | `cycle`, `iopNextEventCycle`, `sCycle[32]` |
| `Counters.h` | `startCycle` (×2), `nextStartCounter` |
| globais do bloco `Cycles` | `EEoCycle`, `nextStartCounter`, `psxNextStartCounter` |

É uma mudança semântica real (contador de 32 bits **dá a volta** a cada ~14,6 s a 294 MHz), não
cosmética. E é justamente por dar a volta que uma extensão-com-zero ingênua está errada: um estado
capturado logo antes de uma volta tem o `cycle` perto de `0xFFFFFFFF` e os ciclos de evento já
enrolados para valores pequenos. Zero-extender os dois transforma *"o evento é daqui a 100 ciclos"*
em *"o evento foi há 4 bilhões de ciclos"*.

---

## 3. O achado: o upstream já resolveu isto

`pcsx2/SaveStateLegacy.cpp` — **1.168 linhas**, que não existem na nossa árvore:

```cpp
bool SaveStateLegacy::IsSupportedVersion(u32 savever)
{
    const u32 major = savever >> 16;
    return major == 0x9A2C || major == 0x9A34;
}
```

É um desserializador do `PCSX2 Internal Structures.dat` das eras AetherSX2 (`0x9A2C`) e NetherSX2
(`0x9A34`). E o cabeçalho dele descreve exatamente o problema da §2:

> *"All 32-bit cycle counters widen relative to their domain base (**WidenCycle**) so wrap-straddling
> deltas survive the u32->u64 conversion."*

O helper já está escrito:

```cpp
// Correct across u32 wraps, which zero-extension is not.
inline constexpr u64 WidenCycle(u32 old_value, u32 old_base, u64 new_base)
{
    return new_base + static_cast<s64>(static_cast<s32>(old_value - old_base));
}
```

O engate no caminho de carga é de duas linhas:

```cpp
if ((savever > g_SaveVersion || (savever >> 16) != (g_SaveVersion >> 16))
        && !SaveStateLegacy::IsSupportedVersion(savever))
    // rejeita
...
if (SaveStateLegacy::IsSupportedVersion(savever))
    state.FreezeInternalsLegacy(error);
else
    state.FreezeInternals(error);
```

---

## 4. E o nosso formato é quase o `0x9A34` que eles já leem

Este é o ponto que torna a coisa barata. Bloco a bloco:

| Bloco | Nosso (`0x9A54`) contra o `_9A34` que eles já leem |
|---|---|
| `cpuRegs` | **Campo por campo idêntico.** 1008 bytes. |
| Bloco `Cycles` | **Idêntico.** `{s32, u32, s32, u32, u32, s32}` = 24 bytes, mesma ordem (nossos nomes são os deles renomeados). |
| `fpuRegs` | 264 bytes = `fpuRegistersWire`. **Idêntico.** |
| `psxRegs` | `psxRegisters_9A34` **mais um `u32 iopCycleEECarry` inserido** entre `iopCycleEE` e `sCycle[32]`. 812 contra 808 bytes. |

Ou seja: dos quatro blocos de registradores, **três já são exatamente uma era que o leitor entende**
e o quarto difere por um campo de 4 bytes — um campo que a versão nova **ainda tem**, então o
mapeamento é 1:1.

---

## 5. O trabalho concreto

1. `IsSupportedVersion`: acrescentar `0x9A54`.
2. `OldState`: `cpuRegisters_9A54` (alias do `_9A34`), `CyclesBlock_9A54` (alias do `_9A34`),
   `psxRegisters_9A54` (o `_9A34` com `iopCycleEECarry` inserido) — cada um com o
   `static_assert` de tamanho, que é o padrão que eles usam.
3. Percorrer os blocos restantes (contadores, VU, MTVU, IPU, GIF, SIF, SPR, CDVD) confirmando quais
   são invariantes e quais precisam de `WidenCycle`. O varrimento de cabeçalho da §2 diz que a única
   classe de mudança é a dos ciclos — mas isso é indício, não prova por bloco.
4. **PAD e USB:** para as eras Aether/Nether eles declaram `SupportsLegacy() == false` (*"the pads
   keep the type and mode they booted with"*). Para `0x9A54` isso provavelmente **não** é
   necessário — os nossos `Pad::Freeze` e `USB::DoState` já usam o mesmo stream `StateWrapper` que os
   deles, e apareceram idênticos no varrimento. Mas isso torna `SupportsLegacy()` uma propriedade
   **por versão**, não global, e hoje ela é global.
5. Validar **byte a byte contra savestates reais da 1.0.23**, que é o método que eles próprios
   declaram ter usado (*"byte-proven against real state files"*). Nós temos a build e conseguimos
   produzir os arquivos, então esta validação é barata e não depende de aparelho de terceiro.

---

## 6. O que esta análise **não** provou

Registrado porque a diferença entre "medi" e "acho" é a razão de este projeto ter um processo:

- O varrimento compara **a sequência de chamadas** de serialização. Ele **não** vê um struct que
  ganhou um campo mantendo a mesma chamada — foi exatamente assim que a mudança do `fpuRegs` quase
  passou. Compensei conferindo à mão os structs de topo (`cpuRegisters`, `psxRegisters`, `tlbs`,
  `cachedTlbs`, VIF, Sio2, Counters, SPR, Gif, Sif, Hw, IPU), **mas não todos os structs
  serializados em profundidade**.
- Não foram conferidos individualmente: CDVD, os detalhes de `rcntFreeze`/`psxRcntFreeze`, VU micro,
  MTVU, e os blobs por componente de GS e SPU2.
- Nenhum savestate real foi carregado. Toda a análise é de fonte.

O passo 5 acima é o que fecha essas lacunas, e ele é barato.

---

## 7. As alternativas, e por que são piores

| Alternativa | Por que não |
|---|---|
| **Congelar o nosso formato** — travar `g_SaveVersion` em `0x9A54` e reverter as mudanças deles | Carrega divergência permanente no subsistema onde divergir é mais perigoso, e desfaz o alargamento dos contadores, que é uma correção de verdade (o `u32` dá a volta a cada ~15 s). |
| **Conversor externo** (ferramenta que lê `0x9A54` e escreve `0x9A59`) | O mesmo trabalho, num lugar pior: sem acesso ao estado vivo, teria de reproduzir o layout inteiro dos dois lados e ficaria desatualizado no próximo bump. O leitor in-engine desserializa direto para o estado vivo. |
| **"Carregue na versão antiga e salve no memory card"** | É a mensagem de erro que os dois lados já mostram. Continua sendo o **fallback necessário** para o que o leitor não conseguir carregar — mas como plano principal é transferir para o cliente um trabalho que a §4 mostra ser barato para nós. |

---

## 8. Conclusão

A frase do handoff — *"o transplante invalida os savestates de todo usuário instalado"* — está
**correta como descrição do que acontece se nada for feito**, e **incorreta como custo inevitável**.

O custo real é: acrescentar uma era a um leitor legado que já existe, cujo caso difícil (o
alargamento dos ciclos com volta) já está resolvido, e cuja era mais próxima (`0x9A34`) já coincide
com três dos nossos quatro blocos de registradores.

Isso **não** elimina a necessidade de avisar no app antes de publicar: o leitor pode não carregar
tudo (PAD/USB são o exemplo declarado), e um aviso honesto de "seus savestates foram migrados;
confira antes de apagar o memory card" é diferente de um aviso de "seus savestates morreram".
