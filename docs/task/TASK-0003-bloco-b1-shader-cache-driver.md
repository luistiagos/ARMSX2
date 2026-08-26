# TASK-0003: Bloco B1 — chavear o cache de shader OpenGL pelo driver

- **Status:** concluída
- **Criada em:** 2026-08-24
- **Concluída em:** 2026-08-24
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** [gs-tela-preta-silenciosa-sem-diagnostico-a07](../bugs/open/gs-tela-preta-silenciosa-sem-diagnostico-a07_2026-08-20T23-15.md), [gs-mali-tela-vermelha-e-page-fault-driver](../bugs/open/gs-mali-tela-vermelha-e-page-fault-driver_2026-08-21T07-39.md)
- **Commit:** assunto `TASK-0003:` — hash no índice de [`README.md`](README.md)
- **Revertida por:** —
- **Publicado em:** 1.0.23 / versionCode 37

## Objetivo

Impedir que binários de programa GL produzidos por um driver sejam entregues a outro. É a causa provável da tela branca e do fecha-sozinho no A07, e explica preto e vermelho pelo mesmo mecanismo.

## Escopo

**Entra:**
- Assinatura FNV-1a de `GL_VENDOR` + `GL_RENDERER` + `GL_VERSION` + formatos de binário, gravada no índice e comparada na leitura (portada do upstream, ~6 linhas).
- Bump de `SHADER_CACHE_VERSION` (67 → 68) para descartar o cache já envenenado em campo.


**NÃO entra:**
- O cache de pipeline Vulkan, que já valida `pipelineCacheUUID` + `vendorID` + `driverVersion`.
- Qualquer mudança de decisão de renderer.
- **Apagar `gl_programs.*` em `setCustomDriverPath` — item retirado do escopo.** A task foi escrita
  supondo que a troca de driver customizado pudesse envenenar o cache GL. Ao abrir o código,
  `GSConfig.CustomDriverPath` é lido **apenas** por `VKLoader.cpp`: é recurso exclusivo do Vulkan e
  nunca troca o driver OpenGL. Além disso, a assinatura é recalculada do driver vivo a cada
  `Open()`, então troca de driver por OTA ou por ANGLE já é detectada sozinha. Implementar seria
  código morto — exatamente o tipo de gambiarra que esta feature existe para eliminar.

## Como validar

Passo 0 do plano: no A07 afetado, apagar `cache/gl_programs.*` via adb e reabrir o jogo. Se abrir, a hipótese está confirmada e esta task a torna permanente. Depois: abrir um jogo, forçar troca de renderer, confirmar no log que o cache foi invalidado e recompilado.

## Resultado

Concluída no código; **validação em aparelho ainda pendente** (ver abaixo).

Três mudanças em `GLShaderCache`, portadas de `ARMSX2/ARMSX2@be72a8e1eb`:

1. `Open()` calcula um FNV-1a sobre `GL_VENDOR` + `GL_RENDERER` + `GL_VERSION` + a lista de
   `GL_PROGRAM_BINARY_FORMATS`, guardado em `m_driver_signature`.
2. `CreateNew()` grava a assinatura no índice logo depois da versão do formato.
3. `ReadExisting()` lê e compara; se diferir, fecha o índice e devolve `false`, o que faz o cache ser
   recriado em vez de alimentar `glProgramBinary()` com bytes de outro driver.

Mais o bump de `SHADER_CACHE_VERSION` 67 → 68, que descarta de uma vez todo cache já gravado em
campo — sem isso, quem já está com o cache envenenado continuaria quebrado mesmo com a correção.

**Validação local:** `ninja -j 4 bin/libemucore.so` → `[6/6]` link OK. Presença confirmada por
**disassembly** de `GLShaderCache::Open`, não por `strings` (literais curtos viram imediatos):

```
mov  w25, #0x193
movk w25, #0x100, lsl #16     -> 0x01000193  (primo FNV-1a)
movk w23, #0x811c, lsl #16    -> 0x811c9dc5  (offset basis)
```

**Pendente:** o Samsung A12 disponibilizado para teste não enumera — nem para o `adb`, nem para o
Windows. Falta confirmar em aparelho real: (a) abrir um jogo, (b) forçar troca de renderer,
(c) ver no log `GL driver signature changed (0x... -> 0x...), invalidating shader cache`. E o Passo 0
do plano continua sendo o teste que confirma a hipótese da tela branca, num A07 afetado.
