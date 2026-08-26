# TASK-0008: Portar a correção de MFIFO/SPR do upstream (`8aca0fe288`)

- **Status:** concluída
- **Criada em:** 2026-08-25
- **Concluída em:** 2026-08-25
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum — a hipótese foi testada e **derrubada**, ver Resultado
- **Commit:** assunto `TASK-0008:` — hash no índice de [`README.md`](README.md)
- **Revertida por:** —
- **Publicado em:** 1.0.23 / versionCode 37

## Objetivo

Trazer `8aca0fe288` — *"VIF/MFIFO: Respect MFIFO empty condition on SPR transfers"* (upstream,
19/03/2026) — que está ausente da nossa árvore e mexe no caminho de DMA apontado como suspeito
principal do crash determinístico de Shadow of the Colossus.

## Por que este commit

A análise do crash (issue upstream [#281](https://github.com/ARMSX2/ARMSX2/issues/281), aberta por
nós e **sem resposta**) já descartou: race, VU1/MTVU, fastmem e — segundo o corpo da issue — o
próprio recompilador do EE, já que reproduz idêntico com `CoreType = Interpreter`. Sobra escrita
host-side na RAM guest, com o SPR DMA como suspeito principal: SotC é o maior usuário de SPR DMA da
biblioteca, e a corrupção atinge a RAM baixa do kernel (`0x19430`) onde o BIOS salvou o EPC.

Comparação com o upstream, feita em 25/08: nosso `SPR.cpp` é **idêntico à versão anterior** ao commit
(2 linhas de diferença, só o ano do copyright) e difere em 17 linhas da posterior. Portanto o commit
nos falta.

O que ele muda, e por que é relevante:

1. Remove de `SPRFROMinterrupt()` a reescrita **incondicional** do endereço de destino:
   ```cpp
   spr0ch.madr = dmacRegs.rbor.ADDR + (spr0ch.madr & dmacRegs.rbsr.RMSK);
   ```
   É uma linha que altera `madr` de DMA — exatamente a classe de coisa que pode fazer uma
   transferência aterrissar no endereço errado.
2. `hwMFIFOResume()` deixa de desistir quando `transferred == 0` e passa a agendar com o tempo real
   do SPR (`cpuRegs.eCycle[DMAC_FROM_SPR]`) em vez de `transferred * BIAS`, que era uma contagem
   inventada. Nossa versão pula o resume do VIF/GIF quando nada entrou no MFIFO, dessincronizando o
   consumidor do ring buffer contra o SPR.

**Ressalva honesta:** o propósito declarado do commit é condição de MFIFO vazio e temporização, não
segurança de endereço. Ele é um **candidato plausível** porque está no subsistema exato que a análise
apontou — não porque esteja provado que corrige este crash. Se não corrigir, o passo seguinte é o
detector de valor-veneno descrito abaixo.

## Escopo

**Entra:**
- `Dmac.h`: assinatura `hwMFIFOResume()` sem argumento.
- `Hw.cpp`: corpo de `hwMFIFOResume` conforme upstream.
- `SPR.cpp`: remoção das duas acumulações de `mfifotransferred`, do gate `mfifotransferred != 0` e
  do bloco que reescrevia `spr0ch.madr`.

**NÃO entra:**
- Remover a variável `mfifotransferred`. O upstream a manteve com um `FIXME` porque ela está no
  formato de savestate; removê-la agora quebraria savestates existentes.
- O detector de valor-veneno (passo 2, task própria se for necessário).
- Guard defensivo no dispatcher — mascara a causa e custa no hot path.

## Como validar

No Galaxy A12 (`SM-A127M`, Exynos 850 / Mali-G52), abrir Shadow of the Colossus e deixar rodar
**no mínimo 6 minutos** — a regra do projeto é 3–5× o tempo do crash, que aqui foi ~110 s.

Verificar com `adb logcat | grep "Unhandled page fault"`. **Não** usar `pidof`: o sistema reinicia o
app após o crash e o PID novo faz parecer que segue vivo.

Também confirmar que os outros três jogos já testados (18 Wheeler, MGS3, Tomb Raider Underworld)
continuam abrindo — o caminho tocado é de DMA e vale para todos.

## Resultado

**O port foi feito e NÃO corrigiu o crash.** A hipótese está derrubada.

Sincronização confirmada: depois do port, `SPR.cpp` e `Dmac.h` ficaram a **2 linhas** do upstream
pós-fix (só o ano do copyright) e `Hw.cpp` a 11 — sendo as 11 restantes a FIFO do SIO, recurso
upstream posterior ao nosso snapshot, sem relação com MFIFO.

Teste no Galaxy A12, Shadow of the Colossus. Crash **byte a byte idêntico** ao de antes:

```
sig=11 pc=0x6e9bc00020 addr=0x12218 write=0
ee pc=44bb910d code=40816000 cause=00000020 epc=0010741c
```

Mesmo `addr`, mesmo `ee pc` corrompido, mesma `cause` (Syscall). Ou seja: o caminho de MFIFO do SPR
— incluindo a reescrita de `spr0ch.madr` que este commit removeu — **não é** o que corrompe a RAM
baixa do kernel.

**Sem regressão:** 18 Wheeler, Metal Gear Solid 3 e Tomb Raider Underworld abrem normalmente com o
port (`GSBoot` emitido, zero page faults).

### Por que o port foi mantido mesmo assim

A mudança é sincronização fiel com o upstream num subsistema onde estávamos atrasados, compila, não
regride nada e remove duas coisas objetivamente erradas: uma reescrita incondicional de `madr` de
DMA e uma contagem de ciclos inventada (`transferred * BIAS`) no lugar do tempo real do SPR. O valor
dela é convergência, **não** a correção do SotC — e é assim que deve ser lida no futuro.

Se em algum momento houver motivo para suspeitar deste caminho de novo, o histórico já registra que
ele foi testado e descartado.

## Próximo passo para o crash

Passo 2 do plano: detector de valor-veneno. Como a corrupção é determinística (`0x44bb910d` em
`0x19430`), não é preciso watchpoint — basta checar, ao fim de cada transferência de DMA, se a RAM
guest naquele offset contém o valor, e logar canal + `madr` + `qwc`. Isso identifica o escritor.
