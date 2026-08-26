# TASK-0013: instrumentar o crash de JIT do Shadow of the Colossus com um detector de valor-veneno

- **Status:** concluída
- **Criada em:** 2026-08-25
- **Concluída em:** 2026-08-25
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum — esta task **instrumenta**, não corrige. O bug alvo é
  [sotc-jit-page-fault-addr-12218](../bugs/open/sotc-jit-page-fault-addr-12218_2026-08-25T02-18.md)
  e continua aberto; declarar que a task o resolve seria mentira registrada.
- **Commit:** — (o vínculo é o prefixo `TASK-0013:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Transformar a hipótese "alguma transferência de DMA sobrescreve um alvo de salto na RAM guest" numa
observação. Hoje o único sinal é o tombstone, que chega depois de a corrupção já ter acontecido e
não diz quem escreveu.

## Por que esta abordagem, e não um watchpoint

O crash reproduz **byte a byte** em dois SoCs diferentes: mesmo `addr=0x12218`, mesmo `write=0`,
mesmo `ee pc=44bb910d`. Corrupção determinística é o caso em que a checagem periódica barata resolve
o mesmo que o watchpoint caro: como o valor sempre chega ao mesmo lugar, basta olhar aquele lugar
depois de cada transferência. Não precisa de watchpoint de hardware, não precisa instrumentar o
recompilador, e o custo é uma leitura de 32 bits alinhada por sinalização de canal.

`hwDmacIrq()` é o único ponto por onde **toda** sinalização de canal passa — VIF0/1, GIF, IPU,
SIF0/1/2, fromSPR/toSPR —, o que faz dele um gancho único em vez de dez.

## Escopo

**Entra:**
- `DebugTools/GuestPoisonWatch.{h,cpp}`: tabela de pares (endereço, valor) vigiados, uma entrada.
- Gancho em `hwDmacIrq()` e reset em `hwReset()`.
- Saída só na **transição** para o valor vigiado — e também na volta, que é informação: diz que o
  valor é transitório e não terminal. Sem transição, sem linha; não há como inundar o log.
- A linha traz canal, `madr`, `qwc`, `tadr`, `chcr`, `ee_pc`, ciclo e os quatro words ao redor do
  endereço — a vizinhança é o que permite dizer **qual** estrutura foi atingida.
- Emitido por `__android_log_print` fora do gate de `Log::GetMaxLevel()`, pelo mesmo motivo da linha
  `GSBoot`: numa instalação padrão todos os sinks nascem em `NONE` e um `Console.Warning`
  desapareceria antes do logcat. A reprodução deste bug é `adb logcat`.

**NÃO entra:**
- Qualquer tentativa de **corrigir** o bug. Só instrumentação.
- Toggle de configuração. O custo é uma leitura por IRQ de DMA e a saída é condicional à detecção;
  um toggle aqui seria uma chave a mais para alguém esquecer de ligar no momento em que precisa.

## O que este detector pode provar, e o que não pode

Ele pode provar **positivamente** que uma transferência de DMA escreveu o valor: se disparar, a
linha nomeia o canal e o intervalo. Isso fecha a investigação.

Ele **não** pode provar o contrário. Se nunca disparar num crash reproduzido, o que ficou
estabelecido é apenas que aquele endereço não recebeu aquele valor via DMA. E aí a primeira coisa a
duvidar é o **endereço**, não a hipótese: `0x44bb910d` está diretamente observado nos dois
tombstones (`ee pc`), enquanto `0x19430` está **inferido** pela análise do handoff. Está escrito
assim no cabeçalho do `.h` para que a próxima pessoa não perca tempo duvidando da parte certa.

## Como validar

1. Compila e linka: `ninja -j 4 bin/libemucore.so` — **feito**. Símbolos confirmados no `.so` com
   `llvm-nm` (`GuestPoisonWatch::Reset()`, `GuestPoisonWatch::OnDmacIrq(int)`) — um `grep` de string
   no `.so` não serve, o clang converte literais curtos em imediatos.
2. Campo: abrir Shadow of the Colossus e rodar ~2 minutos com
   `adb logcat -s NDK_LOG | grep PoisonWatch`. **Não executado** — depende de aparelho com a ROM.

## Resultado

Entregue e linkado. A validação 2 é a que importa e continua pendente de aparelho; até ela
acontecer, esta task entregou uma ferramenta, não uma conclusão.
