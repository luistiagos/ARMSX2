# TASK-0031: trazer o detector de valor-veneno da DMA para o fork

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum (é instrumentação para investigar um, não a correção)
- **Commit:** — (o vínculo é o prefixo `TASK-0031:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Item 6 do inventário de migração de 2026-08-27. Na árvore anterior é a
[TASK-0013](https://github.com/luistiagos/ARMSX2Plus) (`DebugTools/GuestPoisonWatch`), escrita para
investigar o crash de Shadow of the Colossus:

```
Unhandled page fault: sig=11 addr=0x12218 write=0    ee pc=44bb910d
```

Reproduzido byte a byte em **dois SoCs diferentes** — Exynos 850 / Mali-G52 e MediaTek / Mali-G615 —
com a mesma assinatura. Dois fabricantes de driver, duas versões de Android: o defeito é do
recompilador, não do caminho gráfico. `0x44bb910d` não é um PC válido; é dado interpretado como PC.

## O que foi trazido

- `pcsx2/DebugTools/GuestPoisonWatch.{h,cpp}` — cópia direta, sem alteração
- `pcsx2/CMakeLists.txt` — as duas entradas
- `pcsx2/Hw.cpp` — os dois engates: `Reset()` em `hwReset()`, `OnDmacIrq(n)` em `hwDmacIrq()`

Uma leitura de 32 bits alinhada por sinalização de canal de DMA, em memória quente. Só produz saída
na **transição** para o valor vigiado, então não inunda o log.

## É temporário por construção

Some quando o bug fechar. E o cabeçalho carrega o aviso que importa: o par (endereço, valor) veio da
análise dos tombstones, **não de uma derivação a partir do primeiro princípio**. `0x44bb910d` está
observado nos dois tombstones; `0x19430` está *inferido*. Se o detector nunca disparar num crash
reproduzido, a primeira coisa a duvidar é o endereço, não a hipótese de DMA.

## Sobre tocar no core

Isto viola a regra que o próprio [plano do fork](../plano-fork-sobre-upstream.md) estabeleceu —
"toda correção de motor nasce como contribuição ao upstream, não como edição local". A exceção é
deliberada e o plano já a previa: instrumentação temporária para um bug aberto não é candidata a
contribuição, e o delta é de 3 linhas em um arquivo que o upstream mantém. Quando o bug fechar, some
inteiro — inclusive os engates.

## Como validar

| Verificação | Resultado |
|---|---|
| Símbolos no `.so` | `GuestPoisonWatch::Reset()` e `::OnDmacIrq(int)` presentes em `libemucore_4k.so` |
| App após instalar | biblioteca em 12.628, sem regressão |
| Log | silencioso, como esperado sem jogo rodando |

> A verificação foi feita com `llvm-nm` no `.so`, não com `grep` de string no binário — o build
> nativo terminou em 1m46 e um build rápido demais é motivo para desconfiar de que o arquivo novo
> nem foi compilado. Ele foi: o ninja só recompilou as unidades que mudaram.

**Não exercitado:** o disparo do detector. Exige rodar Shadow of the Colossus até o crash, e não há
ROM real disponível neste aparelho.

## Resultado

Entregue, como instrumentação. O bug continua aberto.
