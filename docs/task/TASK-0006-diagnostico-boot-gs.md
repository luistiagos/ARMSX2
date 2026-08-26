# TASK-0006: Emitir o diagnóstico de boot do GS sem depender do log estar ligado

- **Status:** concluída
- **Criada em:** 2026-08-24
- **Concluída em:** 2026-08-24
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** [jni-bridge-nao-resolve-em-thread-nativa](../bugs/done/jni-bridge-nao-resolve-em-thread-nativa_2026-08-24T18-40.md)
- **Commit:** assunto `TASK-0006:` — hash no índice de [`README.md`](README.md)
- **Revertida por:** —
- **Publicado em:** 1.0.23 / versionCode 37

## Objetivo

Tornar o aparelho observável para quem dá suporte. Hoje um relato de campo ("tela preta", "tela
vermelha", "fecha sozinho") não pode ser correlacionado com GPU, driver, API efetiva ou flags de
blending, porque todos os sinks de log nascem em `LOGLEVEL_NONE` numa instalação padrão.

## Escopo

**Entra:**
- `Host::ReportGraphicsBootDiagnostics()`, declarada em `Host.h` e implementada em `main.cpp`.
  Escreve via `__android_log_print` **fora do gate de `Log::GetMaxLevel()`**, no mesmo padrão que
  `Host::ReportErrorAsync` já usa, e chama `NativeApp.onGraphicsBootDiagnostics` por JNI.
- Chamada em `GS.cpp::OpenGSDevice`, depois de `Create()` — é onde as features são finais.
- `NativeApp.onGraphicsBootDiagnostics(String)` envia o evento `graphics-boot` à telemetria com
  modelo, fingerprint, hardware, SDK e versão do app.
- Aviso explícito no logcat quando a ponte JNI falha, em vez de perder o evento em silêncio.

**NÃO entra:**
- Ligar o log por padrão. O resumo sai sozinho; o log completo continua sob o toggle.
- Qualquer mudança de decisão de renderização.

## Como validar

Com o log **desligado** no INI (condição do usuário de campo), abrir um jogo e rodar
`adb logcat -s NDK_LOG`. Deve aparecer uma linha `GSBoot: ...`, e o lado Java deve logar
`ARMSX2-GSBoot` com o mesmo conteúdo.

## Resultado

Concluída e validada no Galaxy A12 (`SM-A127M`, Mali-G52, driver r38p1), com
`RecordAndroidLog = false` e `EnableSystemConsole = false`:

```
I NDK_LOG      : GSBoot: api=OpenGL requested_renderer=-1 gpu_profile=Mali fbfetch=0 texbarrier=0
                 cas=0 cfg_disable_fbfetch=0 cfg_override_texbarriers=-1 serial=SLUS-20915
                 crc=AA31B5BF title="Metal Gear Solid 3 - Snake Eater"
                 driver="... v1.r38p1-01bet0-mbs2v41 ARM Mali-G52 ..."
I ARMSX2-GSBoot: (mesma linha, mais device/fingerprint/sdk/versão)
```

Essa única linha responde tudo que os três bugs gráficos diziam ser impossível saber.

### O que a linha já revelou neste aparelho

- `fbfetch=0 texbarrier=0` com `cfg_disable_fbfetch=0`: **não é o usuário desligando** — é a nossa
  regra de MGS3 por título forçando o caminho caro de cópia de render target. O driver oferece
  framebuffer fetch (`arm=1`), e nós recusamos.
- `cas=0`: confirma o
  [bug do shader CAS](../bugs/done/cas-shader-gles-sem-precisao-mali_2026-08-24T17-08.md).
- `gpu_profile=Mali`: a [TASK-0002](TASK-0002-bloco-a-arquivos-perfil-gpu.md) resolvendo certo.

### Desvio de escopo, deliberado

A primeira versão emitia a linha no logcat mas **o evento de telemetria nunca saía**. A causa era um
defeito pré-existente na ponte JNI, documentado à parte e corrigido aqui porque sem ele metade desta
task não funcionava.
