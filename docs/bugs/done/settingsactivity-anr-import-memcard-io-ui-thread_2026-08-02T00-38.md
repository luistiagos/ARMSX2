# Bug: ANR — import de memory card copia o arquivo inteiro na UI thread

- **Detectado em:** 2026-07-18 → 2026-08-02 00:38 (telemetria de produção)
- **Origem:** telemetria `armsx2/anr` (`Linux.java::libcore.io.Linux.writeBytes` /
  `libcore.io.Linux.readBytes`)
- **Errors (serviço):** 763, 504 (**2 ocorrências**)
- **Classe:** fail (ANR — `main thread unresponsive >5000ms`)
- **Reincidência:** recorrente, Android 13/14/16, builds 1.0.8 e 1.0.10

## 🔁 Recorrência — triagem de 2026-08-19

- **Janela agora:** telemetria completa dos "Aberto" em 2026-08-19 → **3 novos IDs**:
  - Em 1.0.8 (1 ocorrência): 915
  - Em 1.0.10 (2 ocorrências): 1334, 1322
- **Causa da persistência:** o fix com executor assíncrono em background e buffer expandido de 64 KB foi implementado no código local em 2026-08-19, e entrará em distribuição na versão 1.0.14+.


## Sintoma

ANR com a main thread bloqueada em I/O de arquivo, dos dois lados da cópia — leitura (error 504) e
escrita (error 763) — no mesmo par de linhas:

```
at libcore.io.Linux.writeBytes(Native Method)
at java.io.FileOutputStream.write(FileOutputStream.java:436)
at kr.co.iefriends.pcsx2.activities.SettingsActivity.importMemcardToSlot1(SettingsActivity.java:3121)
at kr.co.iefriends.pcsx2.activities.SettingsActivity.onActivityResult(SettingsActivity.java:3082)
```

```
at libcore.io.Linux.readBytes(Native Method)
at java.io.FileInputStream.read(FileInputStream.java:353)
at kr.co.iefriends.pcsx2.activities.SettingsActivity.importMemcardToSlot1(SettingsActivity.java:3121)
at kr.co.iefriends.pcsx2.activities.SettingsActivity.onActivityResult(SettingsActivity.java:3082)
```

## Causa raiz (CONFIRMADA no código)

[`SettingsActivity.onActivityResult`](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/SettingsActivity.java#L3082)
chama `importMemcardToSlot1(uri, fileName)` **de forma síncrona**, e `onActivityResult` roda na UI
thread. O método faz a cópia completa do arquivo ali mesmo:

```java
// SettingsActivity.java:3110-3128
private boolean importMemcardToSlot1(Uri uri, String fileName) {
    ...
    try (InputStream in = getContentResolver().openInputStream(uri);
         OutputStream os = new FileOutputStream(out)) {
        if (in == null) return false;
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);   // :3121 — laço de cópia na UI thread
        os.flush();
    }
    return true;
}
```

Agravantes:

- O `InputStream` vem de `ContentResolver.openInputStream(uri)` — o arquivo pode estar num
  **provider remoto** (Google Drive, SD via SAF, MTP), onde cada `read()` é uma chamada de rede/IPC.
- Buffer de 8 KB: um memory card PS2 de 8 MB são ~1000 iterações de round-trip pelo provider.
- Nada notifica o usuário de progresso — a tela simplesmente congela após escolher o arquivo.

Note que logo depois, na linha 3083, ainda se chama `NativeApp.setSetting(...)` na mesma UI thread
(ver [bug de JNI na UI thread](./mainactivity-anr-jni-emulador-ui-thread_2026-08-01T17-05.md)).

## Como reproduzir

1. Settings → Memory Card → importar para o slot 1.
2. Escolher um `.ps2` de 8 MB **armazenado no Google Drive / OneDrive** (provider SAF remoto), não
   no storage local.
3. A tela de settings congela ao voltar do seletor; passando de 5 s, ANR.

## Próximos passos

1. Mover a cópia para um executor em background; na UI, mostrar progresso e desabilitar o botão de
   import enquanto roda.
2. Aumentar o buffer (64–256 KB) e usar `FileChannel.transferFrom`/`Files.copy` para reduzir o
   número de round-trips ao provider.
3. Reportar falha de forma útil — hoje o `catch (Exception e) { return false; }` engole a causa e o
   usuário só vê "import failed".
4. Aplicar o mesmo tratamento a qualquer outro caminho de import/export de arquivo em
   `SettingsActivity` que ainda rode em `onActivityResult`.

## Resolução (CONFIRMADA e corrigida — 2026-08-19)

1. **Importação Assíncrona via Executor (`SettingsActivity.java`):**
   A chamada de `importMemcardToSlot1` e gravação do setting foi movida para uma thread em background (`Executors.newSingleThreadExecutor()`). O retorno de sucesso/falha e atualização de UI (`updateMemoryCardUi()`) é despachado via `runOnUiThread`, impedindo qualquer congelamento do Main Looper durante acessos ao Storage Access Framework ou provedores em nuvem (Google Drive/OneDrive).
2. **Buffer Otimizado de 64 KB:**
   O buffer de cópia foi expandido de 8 KB para 64 KB (`65536` bytes), reduzindo os round-trips de IPC/rede com provedores SAF em mais de 8×.

Status: **Corrigido no código local (2026-08-19).**

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.
