# Bugs abertos — triagem por linha do produto

Auditoria refeita em **2026-09-03** sobre `feature/fork-upstream-android` (`fe66b785f3`), depois de
atualizar as referências remotas. O `upstream/master` verificado estava em `5fd85d7fc9`.

Havia **29 relatórios**, não 18 como dizia o índice anterior. Eles agora estão separados em:

- [`armsx2-fork/`](armsx2-fork/README.md): **10** bugs que afetam, ou cuja causa continua presente,
  na árvore atual do fork (eram 22; os quatro de rastreabilidade foram fechados em 2026-09-03 pelas
  [TASK-0010](../../task/TASK-0010-corrigir-validador-rastreabilidade.md) e
  [TASK-0011](../../task/TASK-0011-impor-regra-de-commit-mecanicamente.md) e
  [TASK-0078](../../task/TASK-0078-numero-de-task-identifica-neste-ramo.md));
- [`legado-version1/`](legado-version1/README.md): **7** bugs da linha antiga/`version1`, ou ainda
  sem reprodução depois do transplante, que não devem orientar uma correção no fork sem novo teste.

“Do fork” significa **aplicável ao binário atual**, não necessariamente “criado por código nosso”.
Dentro desse grupo a coluna Origem distingue delta do fork, defeito herdado do upstream, ambiente
externo e defeito legado reintroduzido.

## Critério de severidade

- **Alta:** crash/ANR, jogo inutilizável ou saves existentes inacessíveis.
- **Média:** função importante degradada, desempenho ruim ou recuperação difícil, mas há contorno.
- **Baixa:** efeito visual, diagnóstico ou processo; não impede jogar.
- **—:** não há correção da causa no nosso código. A mitigação possível aparece na observação.

A severidade mede o defeito, não a dificuldade da correção. “Implementada” não significa
“validada”: relatórios nessa condição continuam em `open` até cumprir a prova de campo definida
neles.

## ARMSX2-fork atual

| Bug | Origem | Status verificado | Correção possível? | Severidade | Evidência/ação |
|---|---|---|---|---|---|
| [Capa perdida após download](armsx2-fork/biblioteca-jogo-baixado-perde-a-capa_2026-08-28T10-27.md) | delta do fork | **Implementada**, falta validação em aparelho. | Sim, já implementada | **Média** | TASK-0045 preserva `catalogCoverUrl`; há código e teste, mas o próprio relatório exige teste de ponta a ponta. |
| [Download salvo em formato não bootável](armsx2-fork/catalogo-download-entrega-formato-nao-bootavel_2026-08-28T10-27.md) | delta do fork | **Implementada**, falta validação de ponta a ponta. | Sim, já implementada | **Alta** | TASK-0045 filtra/nomeia o formato; TASK-0048 acrescentou extração de 7z/zip. Os testes locais existem, mas não substituem baixar e bootar no aparelho. |
| [Configuração reescrita na UI](armsx2-fork/configuracoes-cada-ajuste-reescreve-o-config-inteiro-na-ui-thread_2026-08-31T18-40.md) | delta do fork | **Parcial.** Recomposição da navegação foi corrigida; gravação/JNI síncronos e loop de quadros continuam. | Sim | **Média** | Coalescer/debounçar persistência com flush no `onPause` e suspender `ControllerAutoScroll` quando parado. |
| [Digitação de 97–450 ms](armsx2-fork/digitar-custa-97-a-450ms-por-tecla-na-thread-da-ui_2026-08-31T21-30.md) | delta do fork | **Parcial.** TASK-0068 reduziu o realce; piso residual e custo proporcional ao catálogo seguem sem causa fechada. | Sim, após perfilar | **Média** | Reperfilar a versão atual; isolar texto/host e o custo do catálogo antes de outra alteração. |
| [Tela preta GL, Mali-G52 r38](armsx2-fork/gl-mali-g52-r38-tela-preta-contornada-nao-corrigida_2026-08-31T19-00.md) | fork sobre núcleo upstream | **Sem correção.** Reproduzido após o merge e a regra global de desvio foi removida. | Provavelmente; causa ainda aberta | **Alta** | Instrumentar present/surface/swap depois do FMV; o upstream novo até `5fd85d7fc9` não traz correção correspondente. |
| [GOS limita clock](armsx2-fork/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md) | ambiente Samsung | **Causa não corrigível pelo app; mitigação concluída.** Detector, aviso, assistente e opt-out já existem. | Não para a causa | — | Manter como limitação de plataforma; não mascarar como bug do emulador. |
| [GPU timing do GL não lê](armsx2-fork/gpu-timing-do-opengl-no-android-nunca-produz-leitura_2026-09-01T10-50.md) | herdado do upstream | **Sem correção.** O código atual ainda usa entry points core com o alvo da extensão. | Sim, hipótese precisa confirmação | **Baixa** | Primeiro medir `glGetError`; se confirmar, usar `EXT_disjoint_timer_query` corretamente. Upstream `5fd85d7fc9` continua igual. |
| [Mali perde device com upscale](armsx2-fork/mali-g52-r38-vulkan-perde-o-device-com-qualquer-upscale_2026-09-02T11-33.md) | driver/hardware | **Sem correção de causa no app/core.** | Não para a causa; só mitigação | — | Não remover o para-quedas de device-lost. Oferecer renderer software/limite por jogo se houver evidência suficiente. |
| [Savestate `0x9A54` rejeitado](armsx2-fork/savestate-formato-9a54-rejeitado-pelo-fork_2026-08-27T09-10.md) | incompatibilidade fork ↔ version1 | **Implementada**, bloqueada na validação. | Sim, já implementada | **Alta** | TASK-0049 implementou leitor e proteção de consumo integral; falta um `.p2s` real da 1.0.23. |
| [Veredito do renderer ausente no relato](armsx2-fork/veredito-do-renderer-automatico-so-chega-a-relato-quando-ha-crash_2026-08-31T19-10.md) | delta do fork | **Implementada**, falta comprovar num relato real sem crash. | Sim, já implementada | **Baixa** | JNI separado e `graphicsBootSummary` já carregam o veredito; a TASK-0065 ainda está em andamento. |

### Fechado desde a última auditoria

| Bug | Quando | O que a medição disse |
|---|---|---|
| [Piso de Z desligado em Mali Vulkan](../done/mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta_2026-08-31T16-30.md) | 2026-09-04 | A [TASK-0064](../../task/TASK-0064-devolver-o-controle-do-piso-de-z.md) devolveu o opt-out e o A/B rodou no `SM-A127M`: a chave chega ao core (o token `no_ps2_z_quantization` some do log), e **o piso de Z não é a causa das linhas verticais** — a imagem é a mesma nos dois braços. |
| [Renderer automático sem recuperação completa](../done/renderer-automatico-sem-rede-de-seguranca-no-fork_2026-08-31T20-00.md) | 2026-09-04 | As duas classes medidas no `SM-A127M`. Crash antes do primeiro quadro: [TASK-0066](../../task/TASK-0066-rede-de-seguranca-do-renderer-automatico.md) — marcador armado, virada para o outro backend, aviso **uma** vez, nada no terceiro arranque, e a escolha explícita limpa o bloqueio. Tela preta com quadros: [TASK-0082](../../task/TASK-0082-acao-de-imagem-nao-apareceu-troca-o-backend.md) — a ação “a imagem não apareceu”, sem classificar pixels; partindo do 007 preto em GL (md5 idêntico a 40 s de distância, 36,7 fps), **duas confirmações puseram o aparelho a jogar** (Vulkan → device-lost → Software com imagem). |

## Linha antiga / version1

| Bug | Situação no fork atual | Decisão |
|---|---|---|
| [Race na cópia assíncrona de assets](legado-version1/copyassetall-async-corrompe-shaders-em-boot_2026-08-21T03-40.md) | A race descrita era da 1.0.17. O fork serializa a cópia antes de inicializar o core; há outros riscos de UI/I/O, mas não esta concorrência. | Não tratar como bug atual. |
| [Watchdog captura a própria sonda](legado-version1/crashreporter-anr-stack-race-ping_2026-08-20T16-04.md) | O `CrashReporter` do fork usa `CountDownLatch`, que é a correção descrita no relatório. | Corrigido no código atual; validar telemetria antes de promover a `done`. |
| [GraphicsHealthMonitor confunde cena escura](legado-version1/graphicshealthmonitor-falso-positivo-cenas-escuras_2026-08-23T13-57.md) | `GraphicsHealthMonitor` não existe no fork. | Legado; não reintroduzir amostragem automática de pixels. |
| [Tela vermelha/page fault Mali](legado-version1/gs-mali-tela-vermelha-e-page-fault-driver_2026-08-21T07-39.md) | Relatório e correções apontam o core embarcado da linha antiga; não há reprodução equivalente registrada no fork. | Legado; abrir novo relatório se reaparecer. |
| [Tela preta silenciosa no A07](legado-version1/gs-tela-preta-silenciosa-sem-diagnostico-a07_2026-08-20T23-15.md) | Caminhos e sinks descritos pertencem à linha antiga. A tela preta atual do A12 tem relatório próprio e evidência diferente. | Legado; não fundir as duas causas. |
| [Toggle de logging perdido em bump](legado-version1/logging-toggle-perdido-em-bump-de-perfil_2026-08-24T17-10.md) | `MigrateAndroidPerformanceDefaults`, ponto da causa, não existe no fork. | Legado. |
| [SotC page fault `0x12218`](legado-version1/sotc-jit-page-fault-addr-12218_2026-08-25T02-18.md) | Foi reproduzido no JIT anterior. O fork recebeu o transplante ARM64; não há reteste pós-transplante. Nenhum commit upstream recente declara corrigir essa assinatura. | **Indeterminado**, não bug confirmado do fork; retestar SotC antes de reabrir aqui. |

## Como manter a separação

Novo relatório só entra em `armsx2-fork/` quando houver ao menos uma destas provas:

1. reprodução num APK desta branch;
2. causa presente no código atual, com o caminho de execução confirmado;
3. incompatibilidade criada especificamente na transição para esta branch.

Sem isso, o relatório fica em `legado-version1/` até o reteste. Ao corrigir, citar a task nos dois
lados e mover para `retest/` ou `done/` conforme a validação disponível.
