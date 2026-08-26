# Bug: Shadow of the Colossus aborta no CPU Thread com page fault em `addr=0x12218`

- **Detectado em:** 2026-08-25 02:18 (teste dirigido no Galaxy A12)
- **Origem:** `CPU Thread` — bloco JIT recompilado (EE/VU x86→ARM64)
- **Errors (serviço):** nenhum nesta sessão; é reprodução local
- **Classe:** crash
- **Reincidência:** já observado no Motorola G86 5G (Mali-G615, MediaTek). **Agora confirmado num
  segundo SoC**, o que é a novidade deste registro.
- **Feature:** nenhuma
- **Tasks que o resolvem:** — (nenhuma ainda; ver hipóteses eliminadas)

## Sintoma

`Shadow of the Colossus (USA)` abre, renderiza e aborta sozinho. O app é reiniciado pelo sistema, o
que faz um `pidof` ingênuo parecer "ainda vivo" — só o logcat revela o crash:

```
F libc  : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 21612 (CPU Thread), pid 21516
F DEBUG : Abort message: 'Unhandled page fault: sig=11 pc=0x6e9b600020 addr=0x12218 write=0'
F DEBUG : #04 pc 000000000000001c  <anonymous:6e9b600000>
```

Tempo até o crash nesta execução: **~110 s** desde a abertura do jogo.

## O que este teste acrescenta

O crash já era conhecido no **Motorola G86 5G** (MediaTek, Mali-G615, Android 16). Agora reproduz
**byte a byte** no **Samsung Galaxy A12** (`SM-A127M`, Exynos 850, Mali-G52, Android 13):

- mesmo `addr=0x12218`
- mesmo `write=0`
- mesmo `pc` dentro de região `<anonymous>` — bloco JIT, endereço varia por realocação do buffer

Dois SoCs, dois fabricantes de GPU-driver e duas versões de Android com a mesma assinatura exata.
Portanto **não é específico de aparelho nem do caminho gráfico** — é o recompilador. O `GSBoot` desta
sessão confirma o lado gráfico saudável e sem workaround: `fbfetch=1 texbarrier=1 gpu_profile=Mali`.

## Como reproduzir

Abrir Shadow of the Colossus e deixar rodando ~2 minutos. Verificar com
`adb logcat | grep "Unhandled page fault"` — **não** confiar em `pidof`, porque o app reinicia.

## Análise anterior (Moto G86), preservada aqui

- Reproduz com o VU1 Recompiler ligado **ou** desligado; desligar só atrasa o crash, porque a cena
  avança mais devagar. O gatilho acompanha progresso de jogo, não tempo de parede.
- `EnableFastmem=false` dá crash idêntico byte a byte, então a corrupção **não** vem do fastmem.
- Sobra: instrução EE traduzida errada produzindo um endereço guest válido-porém-errado, que passa
  igual por fastmem e vtlb.

## Hipótese eliminada — MFIFO/SPR (2026-08-25)

A [TASK-0008](../../task/TASK-0008-port-mfifo-spr-upstream.md) portou o commit upstream
`8aca0fe288` — *"VIF/MFIFO: Respect MFIFO empty condition on SPR transfers"* — que estava ausente da
nossa árvore e removia, entre outras coisas, uma reescrita incondicional de `spr0ch.madr`:

```cpp
spr0ch.madr = dmacRegs.rbor.ADDR + (spr0ch.madr & dmacRegs.rbsr.RMSK);
```

Era o candidato mais forte: mexe em endereço de destino de DMA, no subsistema que SotC mais usa.

**Resultado: não corrigiu.** O crash reproduz byte a byte com o port aplicado — mesmo
`addr=0x12218`, mesmo `ee pc=44bb910d`, mesma `cause=00000020`. Portanto o caminho de MFIFO do SPR
está descartado como fonte da corrupção. O port foi mantido por ser convergência legítima com o
upstream, não como correção deste bug.

## Instrumentação disponível — 2026-08-25 (TASK-0013)

A [TASK-0013](../../task/TASK-0013-detector-valor-veneno-dma.md) acrescentou um **detector de
valor-veneno**: `DebugTools/GuestPoisonWatch`, ligado em `hwDmacIrq()` — o único ponto por onde
toda sinalização de canal de DMA passa. Depois de cada sinalização ele lê 32 bits da RAM guest em
`0x19430` e, se encontrarem `0x44bb910d`, emite no logcat o canal, `madr`, `qwc`, `tadr`, `chcr`,
`ee_pc`, o ciclo e os quatro words ao redor do endereço.

Como a corrupção é determinística, isso substitui um watchpoint sem custar um: a saída só existe na
**transição**, e a checagem é uma leitura alinhada de memória quente.

Como usar:

```
adb logcat -s NDK_LOG | grep PoisonWatch
```

**Isto não corrige o bug** — instrumenta. E é assimétrico no que consegue provar:

- **Se disparar**, a linha nomeia o canal e o intervalo de destino. A investigação fecha.
- **Se não disparar**, o que fica estabelecido é só que *aquele endereço* não recebeu *aquele valor*
  via DMA. A primeira coisa a duvidar então é o **endereço**: `0x44bb910d` está diretamente
  observado nos dois tombstones (é o `ee pc`), enquanto `0x19430` está **inferido**. Não é o mesmo
  grau de evidência e o código diz isso no comentário de topo.

## Próximos passos

1. **Rodar o detector** num aparelho com a ROM (~2 minutos de jogo é o suficiente, pelo tempo de
   crash observado). É o passo mais barato disponível.
2. Correlacionar `addr=0x12218` com o offset de alguma estrutura guest conhecida — e, se o detector
   não disparar, revisar de onde saiu `0x19430`.
3. Verificar se o upstream tem relato equivalente — SotC é o canário de FPS deles para Mali, então é
   provável que rodem o jogo com frequência.
4. **Reavaliar depois do transplante.** O upstream reescreveu o JIT ARM64 inteiro
   (`3e077eff9b`, *"Merge yaps2: arm64 JIT transplant"*), e `pcsx2/arm64` é a área com mais arquivos
   tocados. Este crash é candidato a sumir sozinho — hipótese, não promessa, e testável no spike
   descrito em [`avaliacao-rebase-sobre-upstream.md`](../../avaliacao-rebase-sobre-upstream.md).
