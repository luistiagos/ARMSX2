# MIG-0005: Descompactação Automática de ROMs `.7z` e `.zip` no Catálogo

- **Prioridade:** Média (Ampliação da Cobertura de Títulos do Catálogo)
- **Status:** Implementado na [TASK-0048](../../task/TASK-0048-descompactar-7z-e-zip-no-download.md) (verificado por teste e pelo R8; validação no aparelho pendente)
- **Origem:** TASK-0045 / Backlog do Catálogo de ROMs
- **Documento de referência:** [`docs/task/TASK-0045-baixar-so-formato-bootavel-e-manter-a-capa.md`](../../task/TASK-0045-baixar-so-formato-bootavel-e-manter-a-capa.md)

---

## 1. Contexto e Problema

No catálogo de 12.628 jogos, centenas de links de downloads apontam para arquivos compactados nos formatos `.7z` ou `.zip`.
Na **TASK-0045**, para evitar que o usuário baixasse 2 GB de dados inúteis que o emulador não conseguia abrir diretamente, o aplicativo foi configurado para **rejeitar** arquivos compactados e priorizar apenas formatos diretos (`.chd`, `.iso`, etc.).

Para disponibilizar esses jogos que só existem compactados na web, o app precisa conseguir descompactar o arquivo `.7z` / `.zip` automaticamente após o download e remover o arquivo compactado temporário para economizar espaço em disco.

---

## 2. Análise Técnica

- É necessário incluir suporte à biblioteca de descompactação (como `org.apache.commons:commons-compress` + `org.tukaani:xz` ou implementação leve nativa C++ via `libarchive`/`7z`).
- O fluxo de download precisa de uma etapa adicional: `BAIXANDO` → `EXTRAINDO (X%)` → `CONCLUÍDO`.
- O espaço em disco precisa ser verificado previamente (o aparelho precisa ter espaço suficiente para o arquivo compactado + o arquivo extraído).

---

## 3. Escopo da Implementação

**Arquivos a modificar:**
- `platforms/android/app/build.gradle.kts` (dependências de descompactação)
- `platforms/android/app/src/main/java/com/armsx2/catalog/RomDownloadManager.java` (permitir `.7z`/`.zip` e adicionar callback de extração)
- `platforms/android/app/src/main/java/com/armsx2/catalog/DownloadQueueManager.java`
- `platforms/android/app/src/main/java/com/armsx2/ui/catalog/DownloadsScreen.kt` (status visual "Extraindo...")

---

## 4. Como Validar

1. Selecionar um jogo no catálogo que esteja hospedado em formato `.7z`.
2. Realizar o download.
3. Observar a notificação e a barra de progresso mudarem para "Extraindo...".
4. Ao final, verificar se o arquivo final `.iso` ou `.bin` foi gerado na pasta de ROMs e se o jogo executa normalmente.
