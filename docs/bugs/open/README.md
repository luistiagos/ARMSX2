# Bugs abertos — triagem por linha do produto

Auditoria refeita em **2026-09-03** sobre `feature/fork-upstream-android` (`fe66b785f3`), depois de
atualizar as referências remotas. O `upstream/master` verificado estava em `5fd85d7fc9`.

Havia **29 relatórios**, não 18 como dizia o índice anterior. Eles agora estão separados em:

- [`armsx2-fork/`](armsx2-fork/README.md): **13** bugs (8 na auditoria de 2026-09-03, mais 2 abertos em 2026-09-05, 1 em 2026-09-11 e 2 em 2026-09-21) que afetam, ou cuja causa continua presente,
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

## Critério de complexidade

- **Alta:** falhas de concorrência nativa (threads/deadlocks), anomalias em drivers gráficos proprietários/SO, investigações com profiling profundo em nível de runtime ART/JIT ou alterações no motor nativo.
- **Média:** ajustes de concorrência/I/O assíncrono em UI/Kotlin, fallback defensivo de drivers/hardware ou adaptação de formatos binários/migração de dados.
- **Baixa:** ajustes pontuais em regras de GameDB/configurações, exposição de atalhos/ações na UI ou enriquecimento de strings/dados informativos em telemetria/JNI.

## ARMSX2-fork atual

| Bug | Origem | Status verificado | Correção possível? | Severidade | Complexidade | Evidência/ação |
|---|---|---|---|---|---|---|
| [Configuração reescrita na UI](armsx2-fork/configuracoes-cada-ajuste-reescreve-o-config-inteiro-na-ui-thread_2026-08-31T18-40.md) | delta do fork | **Parcial.** Itens 1 e 3 fechados e medidos na TASK-0084: o ajuste caiu de 145,8 ms para 3,4 ms de thread da UI (escopo Jogo com VM) e o laço de quadros dorme (62,7 → 2,2 trocas/s). Item 2 (recomposição) segue na TASK-0071. | Sim | **Média** | **Média** | Resta o item 2 e o resíduo de 61–76 ms do apply coalescido, que só sai da thread da UI com trava no `MemorySettingsInterface` (core/upstream). |
| [Digitação de 97–450 ms](armsx2-fork/digitar-custa-97-a-450ms-por-tecla-na-thread-da-ui_2026-08-31T21-30.md) | delta do fork | **Parcial, e reperfilado em 2026-09-05** (`gfxinfo framestats` + `simpleperf`, com a busca de Configurações como braço de controle). A [TASK-0086](../../task/TASK-0086-eco-da-busca-nao-recompoe-a-biblioteca.md) tirou o eco da busca de cima da `HomeScreen` — **CPU da thread da UI de 248 → 198 ms por tecla (−20%)** — e **os percentis não se moveram**: a thread está saturada. Piso e acréscimo do catálogo seguem sem causa fechada. | Sim | **Média** | **Alta** | Próximo passo é **medir num APK não-`debuggable`**: 44,8% da CPU da thread da UI durante a digitação é o interpretador do ART, e o ART recusa AOT para pacote `debuggable` (`status=run-from-apk`; `compile -m speed` cai para `verify`). Caminho seguro, sem desinstalar, já registrado na [TASK-0058](../../task/TASK-0058-medir-release-contra-debug.md); o braço de Configurações não precisa de dado nenhum do usuário. O salto de boot da TASK-0079 foi medido e é **plano** (103 quadros com 6318 títulos, 104 com 12) — não é a parcela do catálogo. |
| [Tela preta GL, Mali-G52 r38](armsx2-fork/gl-mali-g52-r38-tela-preta-contornada-nao-corrigida_2026-08-31T19-00.md) | fork sobre núcleo upstream | **Sem correção.** Reproduzido após o merge e a regra global de desvio foi removida. | Provavelmente; causa ainda aberta | **Alta** | **Alta** | Instrumentar present/surface/swap depois do FMV; o upstream novo até `5fd85d7fc9` não traz correção correspondente. |
| [GOS limita clock](armsx2-fork/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md) | ambiente Samsung | **Causa não corrigível pelo app; mitigação concluída.** Detector, aviso, assistente e opt-out já existem. | Não para a causa | — | **Baixa** | Manter como limitação de plataforma; não mascarar como bug do emulador. |
| [Mali perde device com upscale](armsx2-fork/mali-g52-r38-vulkan-perde-o-device-com-qualquer-upscale_2026-09-02T11-33.md) | driver/hardware | **Sem correção de causa no app/core.** | Não para a causa; só mitigação | — | **Média** | Não remover o para-quedas de device-lost. Oferecer renderer software/limite por jogo se houver evidência suficiente. |
| [Savestate `0x9A54` rejeitado](armsx2-fork/savestate-formato-9a54-rejeitado-pelo-fork_2026-08-27T09-10.md) | incompatibilidade fork ↔ version1 | **Implementada**, bloqueada na validação. | Sim, já implementada | **Alta** | **Média** | TASK-0049 implementou leitor e proteção de consumo integral; falta um `.p2s` real da 1.0.23. |
| [Quick Loading sem entrada após merge](armsx2-fork/quick-loading-sem-entrada-apos-merge-da-task-0067_2026-09-11T15-40.md) | merge | Fechado e validado em aparelho físico (`SM-A127M`) pela TASK-0096 em 2026-09-22. | Sim | **Baixa** | **Baixa** | Entrada `⚡` visível no menu do jogo, cálculo de espaço em disco e modal em pt-BR sem chaves cruas. |
| [Ajuste por jogo não chega à camada lida pelo core](armsx2-fork/ajuste-por-jogo-igual-ao-global-nao-vence-o-gamedb_2026-09-05T20-14.md) | delta do fork | **Metade implementada (2026-09-11), sem prova de aparelho.** Medido em 2026-09-05: um ajuste por jogo feito pela **biblioteca** não gerava `gamesettings/<serial>_<CRC>.ini` nenhum, então o GameDB vencia toda escolha por jogo. A [TASK-0089](../../task/TASK-0089-ajuste-por-jogo-na-biblioteca-cria-a-camada-de-jogo.md) (`23498c4976`) faz o arquivo nascer; os três passos de aparelho dela não rodaram. **Continua aberta** a regra "só o que difere do global", que vale nos dois caminhos. | Sim | **Média** | **Média** | Generaliza para toda chave disputada pelo banco. Sem aviso ao usuário. Caso concreto da metade aberta: *Auto Flush* off por jogo no God of War não pina (bug abaixo). Bloqueador de diagnóstico: o core grava esses INIs em `0600` e o pacote não é `debuggable`, então nem `adb` nem `run-as` leem o conteúdo. |
| [God of War lento no POCO C75](armsx2-fork/god-of-war-lento-no-poco-c75-mali-g52_2026-09-11T14-36.md) | herdado do upstream (GameDB) sobre hardware no piso | **Sem medição.** Causa provável derivada do código: os 14 seriais de God of War carregam `autoFlush: 1` e o overlay mobile do upstream não relaxa nenhum. O contorno óbvio (*Auto Flush* off por jogo) não pina, pela linha acima; o que funciona é *Correções de Hardware Manuais* por jogo. | Talvez — e só via upstream | **Média** | **Baixa** | A [TASK-0095](../../task/TASK-0095-autoflush-do-god-of-war-em-gpu-fraca.md) mede e, se a troca valer, propõe ao overlay do upstream. O `DeviceTier` não reconhece o aparelho como fraco (8 núcleos, 6–8 GB): sem task. |

### Fechado desde a última auditoria

| Bug | Quando | O que a medição disse |
|---|---|---|
| [Quick Loading sem entrada após merge](../done/quick-loading-sem-entrada-apos-merge-da-task-0067_2026-09-11T15-40.md) | 2026-09-22 | Validado no `SM-A127M`: a [TASK-0096](../../task/TASK-0096-devolver-a-entrada-do-quick-loading.md) devolveu a `HomeScreen.kt` as ações de menu para discos e ELFs instalados, adicionou a localização em `pt-BR.json`, validou estimativa de espaço (4,3 GB vs 5,9 GB livres) e integração com o SAF. |
| [Veredito do renderer automático ausente no relato](../done/veredito-do-renderer-automatico-so-chega-a-relato-quando-ha-crash_2026-08-31T19-10.md) | 2026-09-22 | Validado no `SM-A127M`: a [TASK-0065](../../task/TASK-0065-veredito-do-renderer-em-todo-relato.md) integrou a ponte JNI `getAutoRendererVerdict()`, populou `sGraphicsBootSummary` centralmente para todo relato não-crash e alimentou o fluxo de recuperação em tempo de execução no menu de pausa da emulação. |
| [Downloads só em `Android/data`](../done/catalogo-download-so-em-android-data-sem-opcao-de-pasta-propria_2026-09-21T00-16.md) | 2026-09-22 | Validado no `SM-A127M`: a [TASK-0099](../../task/TASK-0099-opcao-pasta-propria-download-e-fragile-user-data.md) devolveu a opção de pasta própria de download fora de `Android/data`, adicionou `android:hasFragileUserData="true"`, implementou aviso no modal e validou resiliência de sonda órfã. |
| [Pasta de download da 1.0.x não adotada](../done/catalogo-pasta-de-download-da-1-0-x-nao-e-adotada-pelo-fork_2026-09-21T00-16.md) | 2026-09-22 | Validado no `SM-A127M`: `download_dir_path` das SharedPreferences legadas é adotado e migrado automaticamente para `downloadDir` no arranque do fork. |
| [Pasta de ROMs do app não acompanha a raiz de dados](../done/biblioteca-pasta-de-roms-semeada-uma-vez-nao-acompanha-a-raiz-de-dados_2026-09-21T00-16.md) | 2026-09-21 | Validado no `moto g86 5G` (Android 16): a [TASK-0097](../../task/TASK-0097-pasta-de-roms-do-app-acompanha-raiz-de-dados.md) tornou a pasta de ROMs implícita e multi-volume. Jogos no armazenamento padrão e em armazenamento customizado aparecem juntos em Salvos (Total: 2) e `romsDirs` em SharedPreferences não grava mais caminhos privados. |
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
