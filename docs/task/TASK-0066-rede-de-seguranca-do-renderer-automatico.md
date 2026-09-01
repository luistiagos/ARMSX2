# TASK-0066: rede de segurança do renderer automático, que a linha anterior tinha e o fork perdeu

- **Status:** em andamento
- **Criada em:** 2026-08-31
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [renderer-automatico-sem-rede-de-seguranca-no-fork](../bugs/open/renderer-automatico-sem-rede-de-seguranca-no-fork_2026-08-31T20-00.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0066:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## De onde vem

Item 2 do pacote aberto pela [TASK-0064](TASK-0064-devolver-o-controle-do-piso-de-z.md).

A linha anterior tinha um watchdog: `auto_renderer_boot.tmp` era armado antes de o renderer
automático ser usado e aposentado depois de 600 quadros apresentados ou num shutdown limpo.
Encontrá-lo no arranque seguinte significa que a sessão passada morreu **sem nunca apresentar um
quadro** — o que nenhum valor de retorno de `GSopen()` consegue dizer, porque um crash não é um
valor de retorno. Ele foi escrito em 2026-08-21 depois de um relato de Motorola: Vulkan crashava ao
abrir o jogo, OpenGL na mão funcionava.

O passo 4 do [plano de convergência](../plano-grafico-mali-convergencia-upstream.md) mandou
aposentá-lo, chamando-o de *"a nossa versão cega do mesmo problema"*. Ele foi aposentado. **O
substituto não foi escrito.** Hoje, no fork, `grep` por `auto_renderer_boot` não acha nada.

## ⚠️ O limite desta task, e ele é o ponto mais importante do registro

**Isto NÃO conserta a tela preta do A12, e nada aqui deve dar essa impressão.**

O watchdog dispara quando a sessão anterior **morreu antes de apresentar quadros**. A tela preta do
A12 não mata o processo: a VM, o áudio, os FMVs e o contador de quadros continuam, e o
`BeginPresentFrame` é chamado normalmente. Do lado de dentro, uma sessão preta é indistinguível de
uma sessão boa.

Distinguir as duas exigiria ler pixels — e o plano proíbe isso em letras maiúsculas: *"Classificar
saúde gráfica por amostra de pixel. Já produziu 38 falsos positivos em 6 modelos."*

Então o que esta task cobre é a classe **crash/travamento no arranque do renderer**, que hoje está
descoberta e já mordeu em campo. A classe "apresenta quadros que ninguém vê" fica registrada como
lacuna aberta no bug, com a saída honesta anotada lá (perguntar ao usuário, não adivinhar pelo
pixel).

## O que muda em relação ao desenho antigo

A linha anterior bloqueava **só o Vulkan**: lá o Vulkan era o arriscado e o OpenGL era o porto
seguro. No fork isso não vale mais — no A12 é o OpenGL que falha e a regra `gl-arm-g52-r38-auto-vulkan`
manda o aparelho para o Vulkan. Um port literal protegeria a direção errada.

Portanto o marcador **guarda qual renderer o `auto` escolheu**, e ao encontrar um marcador velho
bloqueia **aquele** e vira para o outro backend de hardware. Simétrico, e por isso serve aos dois
casos sem saber qual aparelho é qual.

O bloqueio vence a regra do banco de drivers de propósito: a regra é um palpite escrito por nós, o
marcador é evidência colhida **naquele aparelho**.

## Escopo

**Entra:**

- `pcsx2/GS/GSUtil.{h,cpp}`, sob `#if defined(__ANDROID__)` — marcador e bloqueio em
  `EmuFolders::Cache`, consultados por `GetPreferredRenderer`, mais os dois limpadores públicos.
- `platforms/android/.../cpp/native-lib.cpp` — armar/aposentar nos ganchos que já existem:
  `Host::BeginPresentFrame` (aposenta em 600 quadros) e `Host::OnVMDestroyed` (shutdown limpo).
- Limpeza do bloqueio quando o usuário escolhe renderer à mão — escolha explícita não é nossa para
  sobrepor.

**Não entra:**

- **Qualquer detecção de tela preta.** Ver o aviso acima.
- **Amostragem de pixel.** Proibida pelo plano, com número medido.
- Mexer na regra `gl-arm-g52-r38-auto-vulkan`, que é a [TASK-0065](TASK-0065-veredito-do-renderer-em-todo-relato.md)
  e o bug que ela registra.

## Como será validado

1. **Compila** — `GSUtil.cpp.o` e `native-lib.cpp.o` na árvore ninja do AGP.
2. **O caminho normal não muda** — sem marcador velho, `GetPreferredRenderer` decide exatamente como
   hoje. É a propriedade que importa: a rede não pode custar nada a quem não caiu nela.
3. **No aparelho** — abrir um jogo, matar o app antes do primeiro quadro (`adb shell am force-stop`),
   reabrir. Esperado: o outro backend, mais o aviso uma única vez. Reabrir de novo: sem aviso.
4. **A saída existe** — escolher o renderer à mão limpa o bloqueio e o aparelho volta ao normal.
