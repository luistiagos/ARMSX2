# Bug: o renderer automático do fork não tem rede de segurança — a da linha anterior foi aposentada sem substituto

- **Detectado em:** 2026-08-31 20:00 (leitura de código, durante o registro da cadeia gráfica do A12)
- **Origem:** comparação entre `feature/fork-upstream-android` e `feature/handoff-end-to-end`
- **Errors (serviço):** não avaliado — o defeito é a ausência de recuperação, não um erro reportado
- **Classe:** fail
- **Reincidência:** o defeito que a rede cobria já aconteceu em campo (Motorola, 1.0.17)
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0066](../../task/TASK-0066-rede-de-seguranca-do-renderer-automatico.md) — **parcialmente**, ver "O que a TASK-0066 não cobre"

## Sintoma

Se o renderer que o `auto` escolhe faz o app fechar sozinho ao abrir um jogo, **o app fecha de novo
na próxima vez, e na seguinte, indefinidamente**. Não há recuperação automática. A saída existe
(Configurações → Renderer → escolher à mão), mas exige que o usuário saiba que ela existe, e que
consiga chegar às Configurações num app que fecha ao abrir jogo.

## O que existia e não existe mais

A linha anterior (`feature/handoff-end-to-end`, `app/src/main/cpp/pcsx2/GS/GSUtil.cpp`) tinha dois
arquivos em `EmuFolders::Cache`:

| arquivo | papel |
|---|---|
| `auto_renderer_boot.tmp` | armado antes de o renderer automático ser usado; aposentado depois de 600 quadros apresentados ou num shutdown limpo |
| `auto_renderer_no_vulkan.tmp` | gravado quando um marcador velho é encontrado. Persistente **de propósito** — sem isso o usuário alternaria entre sessão boa e crash para sempre |

O raciocínio, do comentário original: *"Finding it at startup means the previous run died before
ever presenting one"* — um crash não é valor de retorno, então nenhum `if (!GSopen())` consegue
detectá-lo. O registro do A07 já tinha catalogado por que os três mecanismos existentes não pegavam
esse caso ([gs-tela-preta-silenciosa-sem-diagnostico-a07](gs-tela-preta-silenciosa-sem-diagnostico-a07_2026-08-20T23-15.md), item 4).

Ele foi escrito em 2026-08-21 depois de um relato real: **Motorola, 1.0.17 — Vulkan crashava ao
abrir o jogo, OpenGL na mão funcionava.**

O passo 4 do [plano de convergência](../../plano-grafico-mali-convergencia-upstream.md) mandou
aposentá-lo junto com `IsAllowlistedAndroidVulkanGPU`, chamando-o de *"a nossa versão cega do mesmo
problema"*. A parte de aposentar foi feita. **A parte de substituir não.**

Confirmado: `grep -rn "auto_renderer_boot" pcsx2/ platforms/android/app/src/main/` no fork não
retorna nada.

## Por que "o banco de drivers substitui isso" não fecha o buraco

O banco de regras responde *"este driver tem este defeito conhecido"*. Ele não responde *"este
aparelho, aqui, agora, não conseguiu apresentar um quadro"*. São perguntas diferentes:

- a regra é um **palpite prévio**, escrito por nós, a partir de outro aparelho;
- o marcador é **evidência local**, colhida no aparelho do usuário.

Um driver novo, um aparelho que ninguém testou, ou uma regra escrita larga demais produzem
exatamente o caso que o marcador pega e a regra não.

## O que a TASK-0066 não cobre, e fica aberto aqui

**A classe "apresenta quadros que ninguém vê".** A tela preta do A12 não mata o processo — a VM, o
áudio e o contador de quadros continuam, e `Host::BeginPresentFrame` é chamado normalmente. Do lado
de dentro do emulador, uma sessão preta é **indistinguível** de uma sessão boa.

Distinguir exigiria amostrar pixels, e isso está proibido com número medido no plano: *"Classificar
saúde gráfica por amostra de pixel. Já produziu 38 falsos positivos em 6 modelos."* — o
`GraphicsHealthMonitor` já foi por esse caminho e o registro do falso positivo está em
[graphicshealthmonitor-falso-positivo-cenas-escuras](graphicshealthmonitor-falso-positivo-cenas-escuras_2026-08-23T13-57.md).

**A saída honesta é perguntar, não adivinhar:** uma ação visível durante o jogo (no menu que já
existe) do tipo *"a imagem não apareceu"*, que troca o backend e reinicia. Custa um toque do usuário
e não custa nenhum falso positivo. Fica registrado aqui como decisão de produto pendente — não foi
implementado porque é mudança de interface, não de mecanismo.
