# MIG-0001: Compatibilidade com Savestates Legados da version1 (`0x9A54` → `0x9A59`)

- **Prioridade:** Alta (Prevenção de perda de dados do usuário)
- **Status:** Implementado na [TASK-0049](../../task/TASK-0049-carregar-savestates-0x9A54.md);
  falta a validação com um `.p2s` real da 1.0.23 antes de publicar
- **Origem:** Transplante de árvore (`version1` → `feature/fork-upstream-android`)
- **Documentos de referência:** 
  - [`docs/savestates-preservar-no-transplante.md`](../../savestates-preservar-no-transplante.md)
  - [`docs/bugs/open/savestate-formato-9a54-rejeitado-pelo-fork_2026-08-27T09-10.md`](../../bugs/open/savestate-formato-9a54-rejeitado-pelo-fork_2026-08-27T09-10.md)

---

## 1. Contexto e Problema

A versão `1.0.23` do RetroSystem PS2 (baseada na `version1`) serializava os savestates com o número de versão `0x9A54`. A árvore oficial moderna do `ARMSX2/ARMSX2` no fork utiliza a versão `0x9A59`.

Ao atualizar o aplicativo por cima da versão 1.0.23, se o usuário tentar carregar um savestate antigo (`.p2s`), o emulador falha silenciosamente ou exibe erro de versão incompatível, invalidando todo o progresso salvo fora do memory card.

---

## 2. Análise Técnica

Dos 56 arquivos do core C++ que participam da serialização dos savestates, **54 têm sequência de wire idêntica**.
As diferenças reais são:

1. **Alargamento dos contadores de ciclo:** Mudança de `u32` para `u64`.
2. **Infraestrutura existente no upstream:** O upstream já mantém `pcsx2/SaveStateLegacy.cpp` (1.168 linhas) com a rotina `WidenCycle()` que trata o rollover de 32 bits.
3. **Equivalência estrutural:** O formato `0x9A54` é quase idêntico ao `0x9A34` já suportado pelo `SaveStateLegacy.cpp` (`cpuRegs` de 1008 bytes, `Cycles` de 24 bytes, `fpuRegs` de 264 bytes). A única divergência em `psxRegs` é o campo `u32 iopCycleEECarry`.

---

> ⚠️ **A §2 e a §3 abaixo estão superadas.** Foram escritas a partir de
> [`docs/savestates-preservar-no-transplante.md`](../../savestates-preservar-no-transplante.md), cuja
> hipótese o registro do bug derrubou e a TASK-0049 remediu contra as duas árvores. Três correções:
>
> 1. **`0x9A54` não é uma variante do `0x9A34`.** O bloco vuJIT é de `2 × 96` bytes aqui e de
>    `2 × 160` lá; o SIO já é o formato moderno; `V_VolumeSlide`/`V_ADSR` já são os de hoje. Ler pelo
>    caminho `0x9A34` desalinharia o blob e carregaria estado corrompido — pior que a rejeição atual.
>    O leitor certo é um **espelho do `FreezeInternals()` moderno** com os campos de ciclo estreitos.
> 2. **`SupportsLegacy()` não é ajuste de PAD/USB: é o oposto.** Numa `0x9A54`, `PAD`, `USB`,
>    `Achievements` e `GS` foram gravados pelo mesmo código que os lê hoje, então devem seguir o
>    caminho **moderno**. O que a task fez foi tornar `SupportsLegacy()` **por versão**.
> 3. **Falta um item que a §3 não lista: `SPU2.bin`.** A *self-version* do bloco não se moveu
>    (`0x000e` dos dois lados) enquanto `V_Voice` e `V_Core` mudaram, então o thaw normal aceitaria o
>    bloco e o leria torto. Precisou de um mapeador próprio (`SPU2freeze9A54`).
>
> O escopo real, os tamanhos medidos e o que ainda não foi verificado estão na
> [TASK-0049](../../task/TASK-0049-carregar-savestates-0x9A54.md).

## 3. Escopo da Implementação

**Arquivos a modificar:**
- `pcsx2/SaveStateLegacy.cpp` / `pcsx2/SaveStateLegacy.h`
- `pcsx2/SaveState.cpp`

**Tarefas:**
1. Adicionar `0x9A54` na lista de versões suportadas em `SaveStateLegacy::IsSupportedVersion(u32 version)`.
2. Declarar as estruturas legado `SaveStateLegacy::FreezeData_v9A54` mapeando os blocos de registradores.
3. Implementar a conversão dos contadores de ciclo via `WidenCycle()`.
4. Ajustar `SupportsLegacy()` para tratar módulos periféricos (PAD/USB).

---

## 4. Como Validar

1. Pegar um arquivo `.p2s` gerado legitimamente no RetroSystem PS2 1.0.23 em um jogo real (ex: *Shadow of the Colossus* ou *Dragon Ball Z Budokai Tenkaichi 3*).
2. Carregar o savestate no fork via menu de savestates.
3. Confirmar que a cena, pontuação, posição dos personagens e áudio restauram perfeitamente sem crash.
