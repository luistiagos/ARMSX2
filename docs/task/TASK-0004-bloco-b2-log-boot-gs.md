# TASK-0004: Bloco B2 — publicar o perfil de driver resolvido no `GSDevice` e na linha de boot do GS

- **Status:** concluída
- **Criada em:** 2026-08-24
- **Concluída em:** 2026-08-25
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** [gs-tela-preta-silenciosa-sem-diagnostico-a07](../bugs/open/gs-tela-preta-silenciosa-sem-diagnostico-a07_2026-08-20T23-15.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0004:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Tornar o aparelho observável **no nível do driver**. A TASK-0006 já entregou a linha de boot do GS
fora do gate de log, mas ela diz qual GPU e quais flags — não qual *blob*. E o defeito mora na
versão do driver, não no nome do GPU: é exatamente essa a tese da FEAT-0001.

## Reavaliação de escopo (2026-08-25)

O escopo original desta task foi escrito antes da TASK-0006 existir. Confrontado com a árvore de
hoje, ele se divide em três partes com estados diferentes:

| Item do escopo original | Estado antes desta task |
|---|---|
| ~22 linhas de getters `__fi` + 5 membros no `GSDevice.h` | **3 de ~12 existiam** (`SetRuntimeGPUProfile`, `IsMaliGPUProfile`, `IsAdrenoGPUProfile`). Faltavam todos os do driver. |
| `GpuProfileDetector::Resolve()` no boot do GS, só para preencher e logar | Existia, mas na **sobrecarga de 3 argumentos** — a que deixa `GpuProfileSelection::driver` no fallback conservador. O banco de 27 regras da TASK-0002 nunca foi consultado em runtime. |
| Envio como evento de telemetria | **Feito** pela TASK-0006. |
| Anexo a todo crash nativo | **Não feito.** |

Portanto o que sobrou, e o que esta task entrega, é a metade do *driver*.

## Escopo

**Entra:**
- `GSDevice.h`: membros `m_mobile_gpu_identity`, `m_mobile_gs_tuning`, `m_mobile_driver_profile`,
  `m_is_mediatek_soc`, e os getters/setters correspondentes, incluindo
  `UsesMobileDriverWorkaround()` e `HasMobileDriverBug()` — a superfície que o Bloco C vai consumir.
- `GSDeviceOGL::CheckFeatures`: monta um `MobileDriverContext` (renderer + `GL_VERSION`, que é toda
  a identidade de driver que GL oferece) e passa a chamar a sobrecarga de 4 argumentos de `Resolve`.
- `GSDeviceVK`: a resolução do perfil **muda de lugar**, de `CreateDeviceAndSwapChain` para
  `CheckFeatures`. Motivo em duas linhas: `m_device_driver_properties` — de onde sai o `driverID`,
  que separa o blob da ARM do PanVK da Mesa no mesmo silício — só é preenchido por
  `ProcessDeviceExtensions()`, que roda dentro de `CreateDevice()`. Resolver antes disso produz um
  perfil sem driver; resolver nos dois lugares produz duas linhas de log discordantes, que é o modo
  de falha documentado no topo de `GSFramebufferFetchPolicy.h`.
- `GS.cpp`: a linha `GSBoot` ganha `gpu_driver`, `drv_ver`, `drv_known`, `drv_rules`, `drv_bugs`,
  `drv_wa`, `drv_fallback` e `mediatek`.
- A última linha `GSBoot` passa a ser anexada aos relatos de crash (Java, ANR e nativo) e ao arquivo
  de crash local.

**NÃO entra:**
- Qualquer uso do perfil resolvido para **decidir** algo. Ele é lido só para log — isso é o Bloco C
  ([TASK-0005](TASK-0005-bloco-c-pontos-de-consumo.md)).
- Mudar o default de `m_runtime_gpu_profile` (hoje `Adreno`, que é um palpite errado em todo
  backend que não chama o setter). É mudança de decisão, e foi para a TASK-0005.

## Como validar

1. Compila: `ninja -j 4` nas TUs `GS.cpp`, `GSDeviceOGL.cpp`, `GSDeviceVK.cpp` — **feito, limpo**.
2. Java compila: `gradlew compileUnrestrictedDebugJavaWithJavac` — **feito, limpo**.
3. Campo: abrir um jogo no Moto G86 e num Samsung A-series e confirmar na telemetria um evento
   `armsx2/graphics-boot` com `gpu_driver` diferente de `Unknown` e `drv_rules` maior que zero.
   `drv_fallback=1` num aparelho conhecido significa que o banco não reconheceu o driver — é um
   achado, não um erro de campo.

## Resultado

Entregue conforme o escopo reavaliado. O ponto que exige atenção de quem revisar é a **mudança de
lugar** da resolução no Vulkan: verificado que nada entre `CreateDeviceAndSwapChain` e
`CheckFeatures` lê o perfil (`grep` por `IsMaliGPUProfile|IsAdrenoGPUProfile|GetRuntimeGPUProfile`
devolve `GS.cpp` linha de boot, `GSRendererHW.cpp:5521` e os dois `#define` do header de shader —
todos posteriores a `CheckFeatures`), e que o `VKShaderCache` não consulta perfil algum.

A validação 3 continua **pendente de aparelho**: é a mesma dependência de campo que a §2.1 do
handoff descreve.
