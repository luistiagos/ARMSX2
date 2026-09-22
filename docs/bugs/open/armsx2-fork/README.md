# Bugs aplicáveis ao ARMSX2-fork

Esta pasta contém os relatórios cuja causa afeta ou continua presente na árvore atual.

O status, a viabilidade de correção, a severidade e a evidência de cada item estão no
[`README` da triagem](../README.md#armsx2-fork-atual).

O relatório do **Quick Loading sem entrada após merge** saiu daqui em 2026-09-22: a
[TASK-0096](../../../task/TASK-0096-devolver-a-entrada-do-quick-loading.md) devolveu a `HomeScreen.kt`
as ações de menu para discos e ELFs instalados, adicionou as 14 chaves em `pt-BR.json` e validou no `SM-A127M`
a entrada `⚡`, o cálculo de espaço (4,3 GB) e o seletor SAF. Fechado em
[`done/`](../../done/quick-loading-sem-entrada-apos-merge-da-task-0067_2026-09-11T15-40.md).

O relatório do **veredito do renderer automático ausente no relato** saiu daqui em 2026-09-22: a
[TASK-0065](../../../task/TASK-0065-veredito-do-renderer-em-todo-relato.md) expôs a JNI nativa
`getAutoRendererVerdict()`, populou `sGraphicsBootSummary` centralmente para todo relato não-crash e
alimentou a recuperação de renderizador no menu de pausa da emulação. Validado no `SM-A127M`. Fechado em
[`done/`](../../done/veredito-do-renderer-automatico-so-chega-a-relato-quando-ha-crash_2026-08-31T19-10.md).

Os relatórios de **downloads apenas em `Android/data`** e **pasta de download da 1.0.x não adotada**
saíram daqui em 2026-09-22: a [TASK-0099](../../../task/TASK-0099-opcao-pasta-propria-download-e-fragile-user-data.md)
devolveu a opção de diretório de download próprio, adicionou `android:hasFragileUserData="true"`,
implementou a adoção legada de `download_dir_path` e corrigiu a sonda de escrita para arquivos órfãos.
Ambos validados com sucesso no `SM-A127M`. Fechados em
[`done/`](../../done/catalogo-download-so-em-android-data-sem-opcao-de-pasta-propria_2026-09-21T00-16.md) e
[`done/`](../../done/catalogo-pasta-de-download-da-1-0-x-nao-e-adotada-pelo-fork_2026-09-21T00-16.md).

O relatório do **piso de Z desligado em Mali no Vulkan** saiu daqui em 2026-09-04: a
[TASK-0064](../../../task/TASK-0064-devolver-o-controle-do-piso-de-z.md) devolveu o opt-out que
não existia e o A/B foi executado no `SM-A127M` — a chave chega ao core (o token
`no_ps2_z_quantization` some do log) e, medida a imagem nos dois braços, **o piso de Z não é a
causa das linhas verticais**. Fechado em
[`done/`](../../done/mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta_2026-08-31T16-30.md).

O relatório do **renderer automático sem rede de segurança** saiu daqui em 2026-09-04. As duas
classes do defeito foram medidas no `SM-A127M`: a de crash pela
[TASK-0066](../../../task/TASK-0066-rede-de-seguranca-do-renderer-automatico.md) (marcador, virada
de backend e aviso uma única vez) e a de "apresenta quadros que ninguém vê" pela
[TASK-0082](../../../task/TASK-0082-acao-de-imagem-nao-apareceu-troca-o-backend.md), que dá ao
usuário a ação *"a imagem não apareceu"* — partindo da tela preta do 007, duas confirmações
levaram o aparelho a jogar. Fechado em
[`done/`](../../done/renderer-automatico-sem-rede-de-seguranca-no-fork_2026-08-31T20-00.md).

