# TASK-0021: assinar o fork com a chave de produção e reescrever o publicador com trava

- **Status:** concluída
- **Criada em:** 2026-08-26
- **Concluída em:** 2026-08-26
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0021:` no assunto)
- **Revertida por:** —
- **Publicado em:** — (o publicador existe, mas **nada foi publicado**)

## Objetivo

Fazer o APK do fork ser instalável **por cima** de uma 1.0.23 existente, e impedir por construção
que um APK assinado errado chegue aos clientes.

## O bloqueador que esta task remove

Um APK assinado com certificado diferente **não instala como atualização** — o Android recusa com
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, e a única saída para o usuário é desinstalar, perdendo saves e
memory cards. Verificado antes: o APK do fork saía assinado com

```
CN=Android Debug   f05dc38cf8862626ea7d5c43dc35d2f5de044a06d9928038490dedbbb7c58678
```

porque o `build.gradle.kts` do upstream cai na keystore de debug quando
`armsx2_keystore.properties` não existe — e **não falha** ao fazer isso.

Depois desta task:

```
CN=RetroSystem PS2, OU=Dev, O=Nanodata   d34a788ab0f4fb5b467be5839c4317d66a46525397dfeebdeb40ba4b97c0745a
```

que é exatamente o certificado que a 1.0.23 dos clientes carrega.

## Escopo

**Entra:**
- `platforms/android/armsx2_keystore.properties` + `retrosystem_release.jks` — **não versionados**.
  O `.gitignore` do módulo já cobre os dois (`armsx2_keystore.properties`, `*.jks`), e foi conferido
  com `git check-ignore` antes de copiar. Nenhum segredo entra no repositório.
- `publish-retrosystem-ps2.ps1`, adaptado do `build-and-upload.ps1` da linha anterior.

**NÃO entra:**
- Publicar. Ver a trava abaixo.

## Por que adaptar e não reescrever

O script antigo tem 694 linhas e carrega conhecimento que custou caro: o histórico versionado que
permite rollback, a conferência de ETag/MD5 pós-upload, e sobretudo o entendimento do cache de borda
— o `?v=<versionCode>` no `apkUrl` e o passo que baixa a **URL pública real** para conferir o
SHA-256, porque falar com o R2 pela API S3 pula o cache e não prova nada sobre o que o cliente
recebe.

Reescrever do zero jogaria isso fora. O que mudou é o que estava amarrado à estrutura antiga:

| Antes | Agora |
|---|---|
| versão em `app/build.gradle` | `platforms/android/gradle.properties` (`armsx2.versionCode` / `armsx2.versionName`) |
| build por `deploy_release.ps1` | `platforms/android/gradlew.bat :app:assembleGithubRelease` |
| APK em `dist/` | copiado do caminho do AGP para `dist/` |
| mensagem do guard citando `deploy_release.ps1` | cita a keystore do `build.gradle.kts` do upstream |

O build é chamado **sem flags de identidade**, de propósito: `applicationId`, `versionCode` e
`versionName` são defaults do `gradle.properties` (TASK-0017). Passá-las aqui reintroduziria o modo
de falha que aqueles defaults existem para eliminar.

## A trava de divulgação

`-Announce` é o **único** caminho para a atualização chegar ao usuário. Sem ela o script:

- constrói, confere a assinatura contra a chave oficial, e arquiva o APK em `rgs/ps2/history/`
- **não** sobrescreve o APK de distribuição
- **não** publica o `version.json`

A trava fica **entre os passos 3 e 4**, não no início, porque o histórico é versionado e não está
linkado em lugar nenhum — arquivar não divulga. Os passos 4 (APK de distribuição) e 6
(`version.json`) são os que chegam ao usuário, e o 6 é o que faz os apps já instalados oferecerem a
atualização sozinhos.

Isso é default por decisão, não por cautela genérica: **o fork troca o app inteiro do usuário**, e a
validação em aparelho ainda não foi feita. Um `-Announce` digitado por engano não volta atrás.

## Como validar

1. `apksigner verify --print-certs` no APK do build → `d34a788a…` — **feito, confere**.
2. Sintaxe do script → `[Parser]::ParseFile` sem erros — **feito**.
3. `-DryRun` ponta a ponta contra o R2 real, e um `-Announce` só depois da validação em aparelho.

## Resultado

Entregue. O APK do fork agora sai assinado com

```
CN=RetroSystem PS2, OU=Dev, O=Nanodata   d34a788ab0f4fb5b467be5839c4317d66a46525397dfeebdeb40ba4b97c0745a
```

que é o certificado da 1.0.23 instalada nos clientes — ou seja, **o caminho de atualização existe**:
um usuário em `versionCode 37` pode receber o 38 por cima, sem desinstalar.

Os segredos ficaram fora do repositório: `git check-ignore` confirmou os dois antes da cópia.

**Nada foi publicado, e nada será sem `-Announce`.** O script para depois de arquivar no histórico e
diz, em texto, o que fez e o que deixou de fazer.
