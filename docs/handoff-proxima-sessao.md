# Handoff — estado do trabalho e o que fazer na próxima sessão

- **Atualizado:** 2026-08-26
- **Branch:** `feature/handoff-end-to-end` (a partir de `feature/sync-upstream-oficial`)
- **Para quem pegar isto do zero:** leia primeiro
  [`docs/README.md`](README.md) (regra de commit) e
  [`plano-grafico-mali-convergencia-upstream.md`](plano-grafico-mali-convergencia-upstream.md)
  (por que este trabalho existe).

> A versão anterior deste documento descrevia o trabalho pendente das seções 2.2 a 2.4 e o spike da
> seção 3. **Tudo o que não dependia de aparelho físico foi executado**, nas TASK-0004, 0005, 0012,
> 0013 e 0014. O que sobrou está aqui, e sobrou por razões nomeadas — não por falta de tempo.

---

## 1. Onde estamos

O ciclo de correções gráficas em Mali (1.0.17 → 1.0.22) não era azar: **toda decisão gráfica nossa
partia do nome do GPU ou do nome do jogo, nunca da versão do driver** — que é onde o defeito mora.
A saída é convergir com o `ARMSX2/ARMSX2`, que já resolveu isso com infraestrutura.

**Esse ciclo agora está fechado no código.** Nenhuma decisão do caminho gráfico é tomada por nome de
jogo, e nenhuma é tomada por cor de pixel. A regra de MGS3 por título deixou de existir; o
`GraphicsHealthMonitor` deixou de trocar renderer; a decisão de framebuffer fetch, que existia em
três lugares contraditórios, virou uma chamada a uma função pura.

### Tasks concluídas

| Task | O que fez | Validação |
|---|---|---|
| TASK-0001 | Sistema de rastreabilidade feature/task/bug + validador | — |
| TASK-0002 | Perfil de GPU + banco de 27 regras de driver do upstream | compila; ligado pela TASK-0004 |
| TASK-0003 | Assinatura de driver no `GLShaderCache` + bump do cache | publicada na 1.0.23; **confirmação em campo pendente** |
| TASK-0004 | Perfil de **driver** publicado no `GSDevice` e na linha `GSBoot` | compila e linka; **campo pendente** |
| TASK-0005 | Bloco C: fbfetch decidido pelo banco de drivers; fim da regra por título e da troca automática de renderer | link limpo + 14 testes |
| TASK-0006 | Diagnóstico de boot do GS sem depender do log ligado | validado no Galaxy A12 |
| TASK-0007 | Precisão GLES no shader CAS | validado no Galaxy A12 |
| TASK-0008 | Port do MFIFO/SPR do upstream | **não corrigiu** o SotC; mantido por convergência |
| TASK-0009 | Publicação da 1.0.23 | — |
| TASK-0012 | Portão de boot honra falha de init nativa e `onNewIntent` | compila; **campo pendente** |
| TASK-0013 | Detector de valor-veneno no DMA para o crash do SotC | linkado; **campo pendente** |

### Tasks abertas

- **TASK-0010** e **TASK-0011** — corrigir o validador de rastreabilidade e impor a regra de commit
  mecanicamente (gancho + CI). Não tocam no aplicativo. São da FEAT-0002.

---

## 2. O que sobrou, e por quê

Tudo o que segue está bloqueado por **aparelho físico** ou por **decisão do dono do produto**.
Nenhum item aqui está esperando mais código.

### 2.1. Confirmar a hipótese da tela branca — precisa de um Galaxy A07

É a razão original de tudo. A TASK-0003 é a correção candidata e está publicada desde a 1.0.23, mas
o Passo 0 nunca foi executado: exige um A07 afetado.

O que fazer quando houver um: apagar `<DataRoot>/cache/gl_programs.*` via `adb` e reabrir o jogo. Se
abrir, a hipótese está confirmada. Detalhes no plano, §4 passo 0.

### 2.2. Três validações de campo que agora têm alvo preciso

O código está entregue; falta rodar. Cada uma tem um comando e um sinal esperado:

| O quê | Comando | O que procurar |
|---|---|---|
| Perfil de driver resolvido (TASK-0004) | abrir um jogo; ver a telemetria `armsx2/graphics-boot` | `gpu_driver` diferente de `Unknown`, `drv_rules > 0`. `drv_fallback=1` num aparelho conhecido **é um achado**: o banco não reconheceu o driver |
| MGS3 sem a regra por título (TASK-0005) | MGS3 no A15, Vulkan | renderiza correto e FPS ≥ 1.0.16 |
| Detector de valor-veneno (TASK-0013) | SotC, ~2 min, `adb logcat -s NDK_LOG \| grep PoisonWatch` | uma linha `sotc-jump-target DETECTADO` com canal e `madr` |

A terceira é a mais barata e a que mais informação rende. Ver a ressalva na §2.3.

### 2.3. Crash de JIT do Shadow of the Colossus — instrumentado, não resolvido

Bug em [`docs/bugs/open/sotc-jit-page-fault-addr-12218_2026-08-25T02-18.md`](bugs/open/sotc-jit-page-fault-addr-12218_2026-08-25T02-18.md).
Reproduz em dois SoCs com assinatura idêntica (`addr=0x12218`, `ee pc=44bb910d`). Hipótese de
MFIFO/SPR **eliminada** pela TASK-0008.

A TASK-0013 acrescentou o detector de valor-veneno que o handoff anterior propunha. Ele é
**assimétrico** no que consegue provar, e isso precisa estar claro antes de alguém rodar:

- **Se disparar**, nomeia o canal de DMA e o intervalo de destino. Fecha a investigação.
- **Se não disparar**, estabelece só que *aquele endereço* não recebeu *aquele valor* via DMA. A
  primeira coisa a duvidar passa a ser o **endereço**: `0x44bb910d` está diretamente observado nos
  dois tombstones (é o `ee pc`), enquanto `0x19430` está **inferido**.

Ver também §3: o transplante pode tornar isto desnecessário.

### 2.4. Savestates: preservá-los é viável

**`0x9A54` (nosso) contra `0x9A59` (upstream)** — cinco versões, confirmado no ref de 26/08. Sem
fazer nada, o transplante **invalida os savestates de todo usuário instalado**.

Mas isso é o que acontece se nada for feito, não o custo inevitável. Medido em
[`savestates-preservar-no-transplante.md`](savestates-preservar-no-transplante.md): dos 56 arquivos
que participam da serialização, **54 têm sequência de wire idêntica**, e a única incompatibilidade
real é o alargamento dos contadores de ciclo de `u32` para `u64`. O upstream **já mantém** um leitor
de formatos legados (`SaveStateLegacy.cpp`, 1.168 linhas) cujo caso difícil é exatamente esse
(`WidenCycle`, que trata a volta do contador de 32 bits) — e o nosso formato coincide com a era
`0x9A34` que ele já lê em **três dos quatro blocos de registradores**.

O aviso no app continua necessário, porque o leitor pode não carregar tudo (PAD e USB são o caso
declarado). Mas passa a ser "seus savestates foram migrados, confira antes de apagar o memory card"
em vez de "seus savestates morreram".

---

## 3. O transplante sobre o upstream

O spike foi executado até onde dava sem instalar cadeia de ferramentas nova. **Os resultados estão
em [`spike-transplante-upstream-2026-08-26.md`](spike-transplante-upstream-2026-08-26.md)** e
mudaram três coisas na leitura anterior.

> **Não é um `git rebase`.** `git merge-base HEAD upstream/master` é vazio — somos um snapshot sem
> história partilhada. A operação é partir da árvore do upstream e recolocar o nosso módulo Android
> por cima.

### O que o spike mudou

**1. Uma quarta divergência de JNI apareceu, e é a pior.** `NativeApp_getGameCRC` retorna `jint` do
nosso lado e `jstring` do lado deles. Mesmo nome, tipo de retorno diferente: JNI liga por nome,
então isto **compila, liga e roda**, entregando ao `int` do Java os 32 bits baixos de um ponteiro.
Não crasha — produz um CRC errado em silêncio, e o CRC alimenta capas, overrides de GameDB e a chave
do `GraphicsHealthMonitor`.

**2. O build não é o gargalo, e a árvore deles compila.** Medido dos dois lados: reconstruir e
linkar os 325 TUs do nosso core a `-j 4` custa **5 min 33 s**; a árvore inteira do upstream, a frio,
custa **13 min 45 s** (1.704 alvos, exit 0, `libemucore_4k.so` linkado com os 143 métodos JNI
esperados). O handoff anterior dizia "um dia é otimista para build **e** porte" — o build cabe num
intervalo de café; o porte é que é o dia.

**3. A "Opção A" tem um preço que não estava visível.** A camada de app do upstream tem **63.998
linhas** de Kotlin/Java em **Compose** (20 XML de recurso). A nossa tem 24.096 em XML/Views (115
XML). "Nossa camada sobre o core deles" significa descartar 64 mil linhas deles — assistente de
configuração, hub de definições, atualizador — para preservar 24 mil nossas. Pode continuar sendo a
escolha certa, porque catálogo e fila de download são nossos e não existem lá. Mas não é neutro.

### O que já está verificado e não quebra

| Item | Estado |
|---|---|
| `applicationId come.nanodata.armsx2` | **seguro** — é `-Parmsx2.applicationId` no gradle deles, propriedade de linha de comando |
| Nome do `PCSX2-Android.ini` | **idêntico** nos dois lados |
| Namespace JNI `Java_kr_co_iefriends_pcsx2_*` | **preservado** no upstream |
| Mecanismo de atualização in-app | **conflita** — eles têm `UpdaterEntry.kt` com flavors github/play; um dos dois tem de ganhar |

### A ordem que a medição sugere

1. ~~**Construir a árvore do upstream limpa, sem o nosso código.**~~ **Feito, e passou.** Exigiu
   CMake 3.31.6, NDK 28.2.13676358 e `python3 .../shaderc/utils/git-sync-deps` (as dependências do
   shaderc não são vendoradas nem submódulos — vêm da rede). Rust acabou **não** sendo necessário:
   sem cargo, o librashader se desliga sozinho e o build segue.
2. **Decidir Opção A vs B**, com o preço da A visível.
3. Reconciliar as 3 divergências de JNI **antes** de qualquer código Java correr, tratando a de
   `getGameCRC` como bug de corrupção e não como diferença de estilo.
4. Savestates: preservá-los é viável, e mais barato do que parecia — ver
   [`savestates-preservar-no-transplante.md`](savestates-preservar-no-transplante.md).

### A árvore do spike

```
D:/projects/play2/ARMSX2-upstream-spike     # git worktree, detached em 662b114168
```

2,6 GB (700 MB de árvore + 1,9 GB do build da §4b). Para remover:
`git worktree remove --force D:/projects/play2/ARMSX2-upstream-spike`. Vale manter enquanto a decisão
Opção A vs B estiver aberta — é uma árvore já configurada e comprovadamente compilável.

### Um bônus possível

O upstream reescreveu o JIT ARM64 inteiro (`3e077eff9b`, "Merge yaps2: arm64 JIT transplant"), e
`pcsx2/arm64` é a área com mais arquivos tocados. O crash do SotC é candidato a sumir
sozinho. **Hipótese, não promessa** — e agora há como testá-la mais barato que o transplante: o
detector da TASK-0013.

---

## 4. Regras de processo que valem a partir daqui

Nenhum commit em `app/src/`, `scripts/` ou arquivos de build sem uma task em
[`docs/task/`](task/README.md). Uma task = um commit. O agente é quem commita.

Antes de todo push: `python scripts/check_traceability.py`.

E a lição que continua valendo: **antes de escrever qualquer correção, verifique se o upstream já
resolveu.** Três dos cinco defeitos investigados na sessão anterior já tinham resposta lá.

A esta lição, esta sessão acrescenta uma segunda: **quando um registro descreve uma medição, remeça
antes de decidir sobre ela.** Os números de JNI de 18/08 estavam corretos quando foram escritos e
estavam errados oito dias depois — e a diferença era um defeito de corrupção silenciosa.
