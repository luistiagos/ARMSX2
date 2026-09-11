# Bugs aplicáveis ao ARMSX2-fork

Esta pasta contém **11 relatórios** cuja causa afeta ou continua presente na árvore atual.

O status, a viabilidade de correção, a severidade e a evidência de cada item estão no
[`README` da triagem](../README.md#armsx2-fork-atual).

Os dois relatórios que tinham nascido na linha antiga e ficavam aqui por causa confirmada no código
atual — carga de `NativeApp` antes do worker e `getExternalFilesDir()` no caminho da UI — foram
corrigidos e validados em aparelho na
[TASK-0079](../../../task/TASK-0079-boot-nao-toca-o-nativo-nem-o-disco-na-ui.md).

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
