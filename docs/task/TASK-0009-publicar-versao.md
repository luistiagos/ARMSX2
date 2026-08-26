# TASK-0009: Publicar a versão com as correções desta branch

- **Status:** concluída
- **Criada em:** 2026-08-25
- **Concluída em:** 2026-08-25
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum diretamente — leva ao campo as correções das tasks anteriores
- **Commit:** assunto `TASK-0009:` — hash no índice de [`README.md`](README.md)
- **Revertida por:** —
- **Publicado em:** 1.0.23 / versionCode 37

## Objetivo

Tirar os clientes da 1.0.22 — a versão do relato *"o jogo nem abre mais, tela branca e fecha"* — e
levar a campo a correção candidata dessa falha, além das duas correções validadas em aparelho.

## Escopo

**Entra:**
- Bump de `versionCode` e `versionName` (feito pelo próprio `build-and-upload.ps1`).
- Build de release assinado com o certificado oficial, publicado no R2 e `version.json` atualizado.

**NÃO entra:**
- Qualquer mudança de código. O que vai ao ar é exatamente o que já está commitado e validado.
- Merge para `main`. A branch segue como está até o rebase ser decidido.

## O que esta versão leva

| Task | Efeito para o usuário |
|---|---|
| TASK-0003 | Cache de shader OpenGL passa a ser invalidado quando o driver muda — correção candidata da tela branca. O bump de `SHADER_CACHE_VERSION` descarta o cache envenenado já em campo. |
| TASK-0006 | `adb logcat -s NDK_LOG` passa a devolver uma linha `GSBoot:` com GPU, driver, API e flags, **sem o usuário ligar log**. Evento `graphics-boot` na telemetria. |
| TASK-0007 | Sharpening CAS volta a funcionar em Mali; para de gravar um dump de 231 KB por boot. |
| TASK-0002 | Perfil de GPU e banco de drivers do upstream compilados. Ver risco abaixo. |
| TASK-0008 | Sincronização do MFIFO/SPR com o upstream. |

## Risco conhecido e assumido

A TASK-0002 troca o resolvedor de perfil de GPU. O antigo declarava no código:

> `// Per Android policy for this fork: unknown/non-Adreno devices default to Mali profile.`

O do upstream devolve `Unknown` quando nada casa. Consequência:

| Aparelho | Antes | Agora |
|---|---|---|
| Adreno, Mali reconhecido | igual | igual |
| PowerVR, Xclipse | **Mali** | perfil correto |
| GPU não reconhecida | **Mali** | Unknown |

Para Galaxy A07/A15/A12 e Adreno **nada muda**. Para PowerVR e Xclipse é correção. Para uma GPU não
reconhecida é perda do caminho de framebuffer fetch da ARM que recebia por acidente — e **não temos
aparelho desses para testar**.

O risco foi comunicado antes da publicação e a decisão de publicar assim mesmo foi explícita.
Mitigação: a linha `GSBoot` da TASK-0006 agora mostra `gpu_profile=` em campo, então um relato desses
fica diagnosticável em vez de silencioso.

## Como validar

1. `build-and-upload.ps1` conclui sem abortar — ele aborta se o APK não estiver assinado com a chave
   oficial e se a versão já existir no remoto.
2. O passo 7 do script baixa a URL pública real e confere o SHA-256 — é o único teste que prova o que
   o cliente recebe.
3. Depois: instalar no Galaxy A12 pela atualização in-app e confirmar que um jogo abre.

## Resultado

Publicada em 2026-08-25 como **1.0.23 (`versionCode` 37)**.

- APK: 32.150.368 bytes (30,66 MB)
- SHA-256: `b51069f184e7e0fb00755efa080d1a2a0d277658fb08b30534d6fcfa40d6a084`
- Assinado com o certificado oficial (passo 2 confirmou antes de qualquer upload)

Os oito passos passaram, incluindo os dois que realmente importam:

- **Passo 5** (origem R2): tamanho e MD5 conferem.
- **Passo 8** (URL pública, o que o cliente recebe): o `version.json` anuncia 1.0.23/37, a URL do app
  entrega os bytes certos e o link de download manual também já serve a nova versão. O cache de
  borda foi purgado no passo 7, então desta vez o link nu não ficou servindo a versão anterior.

### Falha na primeira tentativa, e a causa

A primeira execução abortou com exit 1 em `deploy_release.ps1:138`. **A causa foi a invocação, não o
projeto:** o script foi chamado com `2>&1`, e no PowerShell 5.1 isso embrulha cada linha de stderr de
executável nativo num `NativeCommandError` e marca falha mesmo com exit code 0 — o gradle só havia
emitido um *warning* de versão de SDK XML.

O bump que essa tentativa já tinha feito (36 → 37) foi revertido antes de repetir, para a numeração
não pular. Ao reexecutar sem a redireção, passou limpo.

**Para a próxima vez:** chamar `build-and-upload.ps1` sem `2>&1`. O stderr já é capturado pela
ferramenta.
