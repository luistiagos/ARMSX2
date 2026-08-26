# TASK-0002: Bloco A — trazer os arquivos autocontidos de perfil de GPU do upstream

- **Status:** concluída
- **Criada em:** 2026-08-24
- **Concluída em:** 2026-08-24
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum (nenhum caminho de renderização muda ainda)
- **Commit:** assunto `TASK-0002:` — hash no índice de [`README.md`](README.md)
- **Revertida por:** —
- **Publicado em:** 1.0.23 / versionCode 37

## Objetivo

Copiar do `upstream/master` os 10 arquivos autocontidos do sistema de perfil de GPU e compilá-los, sem ligar nada ao caminho de renderização. Depois desta task o binário se comporta exatamente como hoje.

## Escopo

**Descoberta ao iniciar:** o fork **já tinha** um port parcial e antigo — `GSGPUProfile.{h,cpp}`
(965 + 4.629 bytes) com apenas `Mali`/`Adreno`, e `GSConfig.AndroidGpuProfileOverride` já existente
e funcionando. Portanto esta task é uma **atualização** desse port, não uma inclusão nova.

**Entra:**
- Substituição de `GSGPUProfile.h` (965 → 8.030 bytes) e `GSGPUProfile.cpp` (4.629 → 15.130).
- Arquivos novos: `GSGPUProfilePrivate.h`, `GSGPUProfileMali.cpp`, `GSGPUProfileAdreno.cpp`,
  `GSGPUProfilePowerVR.cpp`, `GSGPUDriverProfile.cpp` (banco de 27 regras), `GSFramebufferFetchPolicy.h`.
- Entradas no `app/src/main/cpp/pcsx2/CMakeLists.txt`.

**NÃO entra:**
- Os dois arquivos de teste do upstream (`gs_framebuffer_fetch_policy_tests.cpp`,
  `gs_gpu_driver_profile_tests.cpp`). O fork **não tem `tests/`** — não há harness ctest, e montá-lo
  é trabalho próprio. Fica para uma task futura. Mitigação parcial: `GSFramebufferFetchPolicy.h` é
  `constexpr` com `static_assert`, então parte dela é verificada em tempo de compilação.
- Alterar os pontos de chamada. O upstream **manteve o overload de 3 argumentos** de `Resolve()`,
  então `GSDeviceOGL.cpp:673` e `GSDeviceVK.cpp:2663` compilam sem tocar em nada.
- Consumir qualquer campo novo (`driver`, `gs_tuning`, `gpu`, `is_mediatek_soc`) — Blocos B2 e C.

## Como validar

`assembleUnrestrictedDebug` compila. Nenhuma diferença de comportamento observável: mesmo renderer, mesmo FPS, mesmos logs.

## Resultado

Concluída. Os 5 TUs novos compilam e `libemucore.so` linka (`[154/154]`). Confirmado no binário por
símbolo, não por string: `s_driver_rules`, `s_mali_specs`, `s_adreno_specs`, `ResolveMaliProfile`,
`ResolveDriverProfile`, `ResolvePowerVRProfile`. Buscar os nomes das regras com `strings` não acha
nada — o clang converte literais curtos em imediatos, como já registrado no projeto.

### Mudança de comportamento — não é neutra como eu havia previsto

Eu apresentei esta task como "risco zero, binário idêntico". **Está errado**, e o detalhe importa.

O detector antigo tinha esta política explícita no código:

> `// Per Android policy for this fork: unknown/non-Adreno devices default to Mali profile.`

Ou seja: **tudo que não parecesse Adreno recebia o perfil Mali.** O resolvedor do upstream não faz
isso — quando nada casa, ele devolve `RuntimeGpuProfile::Unknown`.

| Aparelho | Antes | Agora |
|---|---|---|
| Adreno | Adreno | Adreno (igual) |
| Mali reconhecido pelo nome | Mali | Mali (igual) |
| PowerVR | **Mali** | PowerVR |
| Xclipse | **Mali** | Xclipse |
| Nada reconhecido | **Mali** | Unknown |

Para os aparelhos-alvo desta feature — Galaxy A07 (Mali-G57) e A15 — **nada muda**: o nome casa com
`LooksLikeMali`. Para PowerVR e Xclipse a mudança é uma correção: eles recebiam otimizações de Mali
que não são deles.

O risco real está na última linha: um aparelho cujo `GL_RENDERER` não identifique nem Mali nem
Adreno perde o perfil Mali que recebia por omissão, e com ele o caminho de framebuffer fetch da ARM.
Isso é o comportamento deliberado do upstream, e mantê-lo é o ponto da convergência — mas é uma
mudança observável e fica registrada aqui em vez de ser descoberta por relato de cliente.
