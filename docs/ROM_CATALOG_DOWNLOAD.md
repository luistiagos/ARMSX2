# ROM Catalog Download — Plano de Implementação para ARMSX2

## Objetivo

Adicionar ao ARMSX2 um catálogo de ROMs PS2 onde o usuário vê a lista de jogos disponíveis,
seleciona um e o app faz o download automaticamente — sem sair do app, sem configurar nada.
BIOS será embarcada no APK. Inspirado no Lemuroid, adaptado para a arquitetura do ARMSX2.

---

## Visão do Usuário (UX Final)

1. Abre ARMSX2 → vê a lista de jogos como hoje (jogos locais)
2. Toca no drawer → opção **"Catálogo PS2"** nova no menu
3. Tela de catálogo aparece: lista/grid de jogos PS2 com capa e título
4. Usuário toca num jogo → dialog de confirmação: **"Baixar e jogar?"**
5. Barra de progresso com pause/cancelar durante download (~1-10 GB por jogo)
6. Download completo → jogo aparece automaticamente na lista principal
7. Usuário toca o jogo → emulação inicia normalmente

---

## Análise da Infraestrutura Existente no ARMSX2

### O que já existe e pode ser reutilizado

| Componente | Arquivo | Reuso |
|-----------|---------|-------|
| Download HTTP | `MainActivity.java` → `downloadCoverToDirectory()` | Base do padrão, adaptar para ROMs grandes |
| RecyclerView grid/list | `GamesAdapter` em `MainActivity.java` | Reutilizar padrão para CatalogAdapter |
| Layouts de item | `item_game.xml`, `item_game_list.xml` | Reutilizar ou copiar para catálogo |
| Drawer navigation | `nav_drawer_menu.xml`, `DrawerLayout` | Adicionar item "Catálogo PS2" |
| Threading | Java `Thread` + `runOnUiThread()` | Manter padrão existente |
| DataDirectoryManager | `DataDirectoryManager.java` | Localizar onde salvar ROMs |
| SharedPreferences | `"armsx2"` preferences | Persistir estado de downloads |
| Capa de imagens | `downloadCoverToDirectory()` | Baixar capas das ROMs do catálogo |

### O que precisará ser criado

- `CatalogActivity.java` — tela do catálogo
- `CatalogEntry.java` — modelo de dados de um item do catálogo
- `CatalogParser.java` — lê e parseia o `catalog_manifest.txt`
- `RomDownloadManager.java` — gerencia download de ROM com progresso/pause/cancel
- `CatalogAdapter.java` — RecyclerView adapter para a lista do catálogo
- `catalog_manifest.txt` — arquivo de manifesto (embarcado no APK)
- Layouts XML para a nova tela
- Item no drawer para acessar o catálogo

---

## Formato do catalog_manifest.txt

Arquivo de texto simples, uma ROM por linha, separado por `|`:

```
filename.ext|título do jogo|url_da_capa|url_download
filename.ext|título do jogo|url_da_capa|url_download
```

**Exemplo:**
```
Gran Turismo 3 A-spec (USA).chd|Gran Turismo 3 A-spec|https://cdn.covers.io/ps2/gt3.jpg|https://huggingface.co/datasets/luisluis123/ps2sets/resolve/main/roms/Gran%20Turismo%203%20A-spec%20(USA).chd
God of War (USA).chd|God of War|https://cdn.covers.io/ps2/gow.jpg|https://huggingface.co/datasets/luisluis123/ps2sets/resolve/main/roms/God%20of%20War%20(USA).chd
Shadow of the Colossus (USA).chd|Shadow of the Colossus|https://cdn.covers.io/ps2/sotc.jpg|https://huggingface.co/datasets/luisluis123/ps2sets/resolve/main/roms/Shadow%20of%20the%20Colossus%20(USA).chd
```

**Campos:**
| Campo | Descrição |
|-------|-----------|
| `filename.ext` | Nome do arquivo ROM (sem path). Extensão preferida: `.chd` (menor tamanho) |
| `título do jogo` | Nome display na UI |
| `url_da_capa` | URL JPEG/PNG da capa (pode ser string vazia) |
| `url_download` | URL direta de download do arquivo ROM |

**Localização no projeto:**
```
app/src/main/assets/catalog_manifest.txt
```

**Versão do catálogo** — linha especial no topo (opcional):
```
#VERSION=1
Gran Turismo 3 A-spec (USA).chd|Gran Turismo 3 A-spec|...|...
```

---

## BIOS Embarcada

PS2 requer BIOS para funcionar. Embarcar no APK evita que o usuário precise configurar.

**Localização no projeto:**
```
app/src/main/assets/bios/
└── scph10000.bin   (ou outro arquivo BIOS válido)
```

**Extração no primeiro boot** (em `DataDirectoryManager.java` ou `BootSplashActivity.java`):
```java
// Pseudocódigo — já existe padrão similar em DataDirectoryManager.copyResources()
File biosDir = new File(getDataRoot(), "bios");
biosDir.mkdirs();
File biosFile = new File(biosDir, "scph10000.bin");
if (!biosFile.exists()) {
    copyAssetToFile("bios/scph10000.bin", biosFile);
}
NativeApp.setSetting("Filenames", "BIOS", "string", biosFile.getAbsolutePath());
NativeApp.refreshBIOS();
```

**Impacto no APK:** BIOS PS2 tem ~4 MB. Aceitável no APK.

**Atenção:** Distribuição de BIOS é legalmente sensível. A BIOS deve ser obtida de fonte
legítima (extraída de hardware próprio). Este documento não endossa distribuição ilegal de BIOS.

---

## Arquitetura da Funcionalidade

```
assets/catalog_manifest.txt
         │
         ▼
CatalogParser.java ──── lê e parseia ──── List<CatalogEntry>
         │
         ▼
CatalogActivity.java
    ├── CatalogAdapter (RecyclerView)
    │       └── item_catalog.xml (capa + título + status)
    ├── Busca/filtro por título
    └── Toque no item → RomDownloadDialog
              │
              ▼
       RomDownloadManager.java
           ├── download com HttpURLConnection
           ├── progresso via callback/interface
           ├── pause / resume / cancel
           └── salva ROM em {dataRoot}/roms/
                      │
                      ▼
              MainActivity recarrega lista
              (ROM aparece na lista de jogos)
```

---

## Detalhamento dos Componentes

### 1. CatalogEntry.java

```java
public class CatalogEntry {
    public String fileName;     // "Gran Turismo 3 A-spec (USA).chd"
    public String title;        // "Gran Turismo 3 A-spec"
    public String coverUrl;     // URL da capa
    public String downloadUrl;  // URL de download da ROM
    
    // Estado local (não vem do manifest)
    public boolean isDownloaded;   // arquivo existe e tamanho > 0
    public boolean isDownloading;  // download em andamento
    public float downloadProgress; // 0.0 – 1.0
    
    public File getLocalFile(String romsDir) {
        return new File(romsDir, fileName);
    }
}
```

---

### 2. CatalogParser.java

```java
public class CatalogParser {
    // Lê assets/catalog_manifest.txt e retorna lista de entradas
    public static List<CatalogEntry> parse(Context context) {
        List<CatalogEntry> entries = new ArrayList<>();
        try (InputStream is = context.getAssets().open("catalog_manifest.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.trim().isEmpty()) continue;
                String[] parts = line.split("\\|", 4);
                if (parts.length < 4) continue;
                CatalogEntry entry = new CatalogEntry();
                entry.fileName    = parts[0].trim();
                entry.title       = parts[1].trim();
                entry.coverUrl    = parts[2].trim();
                entry.downloadUrl = parts[3].trim();
                entries.add(entry);
            }
        } catch (IOException e) {
            // log error
        }
        return entries;
    }
    
    // Marca quais entradas já estão baixadas
    public static void markDownloaded(List<CatalogEntry> entries, String romsDir) {
        for (CatalogEntry entry : entries) {
            File f = entry.getLocalFile(romsDir);
            entry.isDownloaded = f.exists() && f.length() > 0;
        }
    }
}
```

---

### 3. RomDownloadManager.java

Responsável por um download por vez. Suporta pause, resume e cancel.

**Interface de callback:**
```java
public interface DownloadCallback {
    void onProgress(long bytesDownloaded, long totalBytes);
    void onComplete(File romFile);
    void onError(String message);
    void onCancelled();
}
```

**Fluxo interno:**
```
downloadRom(entry, destDir, callback)
  → cria Thread de download
  → abre HttpURLConnection com URL do entry.downloadUrl
  → se arquivo parcial existe: adiciona header "Range: bytes=X-" (resume)
  → lê stream em chunks de 8KB
  → a cada chunk: notifica callback.onProgress()
  → verifica flag isPaused: se true, aguarda (Thread.sleep loop)
  → verifica flag isCancelled: se true, deleta arquivo parcial, chama callback.onCancelled()
  → ao terminar: move .part para nome final, chama callback.onComplete()
  → erro de rede: chama callback.onError()
```

**Implementação:**
```java
public class RomDownloadManager {
    private volatile boolean isPaused = false;
    private volatile boolean isCancelled = false;
    private Thread downloadThread;

    public void downloadRom(CatalogEntry entry, File destDir, DownloadCallback callback) {
        isCancelled = false;
        isPaused = false;
        downloadThread = new Thread(() -> {
            File partFile = new File(destDir, entry.fileName + ".part");
            File finalFile = new File(destDir, entry.fileName);
            try {
                URL url = new URL(entry.downloadUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(120_000);

                // Suporte a resume
                long existingBytes = partFile.exists() ? partFile.length() : 0;
                if (existingBytes > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
                }

                conn.connect();
                long contentLength = conn.getContentLengthLong();
                long totalBytes = existingBytes + contentLength;

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(partFile, existingBytes > 0)) {

                    byte[] buf = new byte[8192];
                    long bytesWritten = existingBytes;
                    int read;

                    while ((read = in.read(buf)) != -1) {
                        // Pause check
                        while (isPaused && !isCancelled) {
                            Thread.sleep(200);
                        }
                        // Cancel check
                        if (isCancelled) {
                            out.close();
                            partFile.delete();
                            notifyUi(() -> callback.onCancelled());
                            return;
                        }
                        out.write(buf, 0, read);
                        bytesWritten += read;
                        final long bw = bytesWritten;
                        notifyUi(() -> callback.onProgress(bw, totalBytes));
                    }
                }

                // Move .part → final
                partFile.renameTo(finalFile);
                notifyUi(() -> callback.onComplete(finalFile));

            } catch (Exception e) {
                notifyUi(() -> callback.onError(e.getMessage()));
            }
        });
        downloadThread.start();
    }

    public void pause()  { isPaused = true; }
    public void resume() { isPaused = false; }
    public void cancel() { isCancelled = true; }
    
    private void notifyUi(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
```

**Onde salvar as ROMs:**
```java
File romsDir = new File(DataDirectoryManager.getDataRoot(context), "roms");
romsDir.mkdirs();
```

---

### 4. CatalogActivity.java

Activity principal do catálogo. Segue padrão visual do ARMSX2.

**Layout `activity_catalog.xml`:**
```
CoordinatorLayout
├── AppBarLayout
│   └── Toolbar (título "Catálogo PS2", busca)
└── RecyclerView (grid 2 colunas ou lista)
```

**Fluxo:**
```java
onCreate()
  → entries = CatalogParser.parse(this)
  → CatalogParser.markDownloaded(entries, romsDir)
  → adapter = new CatalogAdapter(entries, this::onEntryClick)
  → recyclerView.setAdapter(adapter)

onEntryClick(CatalogEntry entry)
  → if entry.isDownloaded: lança jogo direto (via Intent para MainActivity)
  → else: mostra RomDownloadDialog

onSearchQuery(String query)
  → filtra entries por título contendo query
  → adapter.updateData(filteredEntries)
```

**Botão no drawer** (`nav_drawer_menu.xml`):
```xml
<item
    android:id="@+id/nav_catalog"
    android:icon="@drawable/ic_download"
    android:title="@string/catalog_title" />
```

No `MainActivity.java` no handler do drawer:
```java
case R.id.nav_catalog:
    startActivity(new Intent(this, CatalogActivity.class));
    break;
```

---

### 5. CatalogAdapter.java

RecyclerView adapter — segue padrão do `GamesAdapter` existente.

**Layout `item_catalog.xml`:**
```
CardView
├── ImageView (capa — proporção 2:3)
├── TextView (título)
├── ProgressBar (visível durante download)
├── TextView (tamanho / "Baixado" / "Baixando X%")
└── ImageView (ícone de download ou check)
```

**Estados visuais do item:**
| Estado | Visual |
|--------|--------|
| Não baixado | Capa + título + ícone download |
| Baixando | Capa + título + ProgressBar + "42%" + botão pause |
| Pausado | Capa + título + ProgressBar pausada + botão resume |
| Baixado | Capa + título + ícone check verde |

**Carregamento de capa:** Adaptar o `downloadCoverToDirectory()` existente do `MainActivity`.
Capas são salvas em `{dataRoot}/armsx2_covers/catalog/` para cache.

---

### 6. RomDownloadDialog

Dialog de confirmação + progresso — mostrado quando usuário toca item não baixado.

**Layout `dialog_rom_download.xml`:**
```
AlertDialog
├── ImageView (capa — preview)
├── TextView (título do jogo)
├── [estado: IDLE]     → "Baixar esta ROM?" + botão Baixar + botão Cancelar
├── [estado: BAIXANDO] → ProgressBar + "X MB de Y MB (Z%)" + botão Pause + botão Cancelar
├── [estado: PAUSADO]  → ProgressBar + "Pausado" + botão Continuar + botão Cancelar
├── [estado: ERRO]     → "Erro: mensagem" + botão Tentar Novamente + botão Fechar
└── [estado: PRONTO]   → auto-fecha e lança o jogo
```

**Integração com RomDownloadManager:**
```java
// Ao confirmar download:
downloadManager.downloadRom(entry, romsDir, new DownloadCallback() {
    @Override public void onProgress(long bytesDownloaded, long totalBytes) {
        // Atualiza ProgressBar e texto de progresso
        updateProgress(bytesDownloaded, totalBytes);
    }
    @Override public void onComplete(File romFile) {
        // Notifica MainActivity para recarregar lista
        dismiss();
        onDownloadComplete.accept(entry);
    }
    @Override public void onError(String message) {
        showErrorState(message);
    }
    @Override public void onCancelled() {
        dismiss();
    }
});
```

---

### 7. Recarregar Lista Após Download

Após download, `MainActivity` deve detectar o novo arquivo e adicioná-lo à lista de jogos.

**Opção mais simples** — `CatalogActivity` passa um resultado para `MainActivity`:
```java
// Em CatalogActivity, ao completar download:
Intent result = new Intent();
result.putExtra("downloaded_rom_path", romFile.getAbsolutePath());
setResult(RESULT_OK, result);

// Em MainActivity.onActivityResult():
if (resultCode == RESULT_OK) {
    String romPath = data.getStringExtra("downloaded_rom_path");
    // Adiciona à lista de jogos existente
    refreshGamesList();
}
```

**Alternativa** — `MainActivity` monitora a pasta `roms/` com `FileObserver`:
```java
FileObserver romsObserver = new FileObserver(romsDir.getAbsolutePath()) {
    @Override public void onEvent(int event, String path) {
        if (event == FileObserver.CLOSE_WRITE) {
            runOnUiThread(() -> refreshGamesList());
        }
    }
};
```

---

## Estrutura de Diretórios no Dispositivo

```
{dataRoot}/                          ← context.getExternalFilesDir(null)
├── bios/
│   └── scph10000.bin               ← BIOS extraída dos assets no primeiro boot
├── roms/
│   ├── Gran Turismo 3 A-spec (USA).chd     ← ROM baixada do catálogo
│   ├── God of War (USA).chd.part           ← Download em andamento
│   └── Shadow of the Colossus (USA).chd
├── armsx2_covers/
│   └── catalog/
│       ├── Gran Turismo 3 A-spec (USA).jpg  ← Capa cacheada
│       └── God of War (USA).jpg
├── emu.ini
├── memcards/
└── sstates/
```

---

## Novos Arquivos a Criar

```
app/src/main/
├── assets/
│   ├── catalog_manifest.txt             ← manifesto de ROMs
│   └── bios/
│       └── scph10000.bin                ← BIOS embarcada
├── java/kr/co/iefriends/pcsx2/
│   ├── catalog/
│   │   ├── CatalogEntry.java            ← modelo de dados
│   │   ├── CatalogParser.java           ← parseia o manifesto
│   │   ├── CatalogAdapter.java          ← RecyclerView adapter
│   │   └── RomDownloadManager.java      ← download com pause/resume/cancel
│   └── activities/
│       └── CatalogActivity.java         ← tela principal do catálogo
└── res/
    ├── layout/
    │   ├── activity_catalog.xml         ← layout da tela catálogo
    │   ├── item_catalog.xml             ← item da lista do catálogo
    │   └── dialog_rom_download.xml      ← dialog de confirmação/progresso
    ├── menu/
    │   └── menu_catalog.xml             ← menu da toolbar (busca, filtro)
    └── values/
        └── strings_catalog.xml          ← strings novas
```

---

## Arquivos Existentes a Modificar

| Arquivo | Mudança |
|---------|---------|
| `res/menu/nav_drawer_menu.xml` | Adicionar item "Catálogo PS2" |
| `activities/MainActivity.java` | Handler do drawer → lançar `CatalogActivity`; `onActivityResult` para recarregar lista |
| `activities/BootSplashActivity.java` (ou `DataDirectoryManager.java`) | Extrair BIOS dos assets no primeiro boot; chamar `NativeApp.setSetting("Filenames", "BIOS", ...)` |
| `app/build.gradle` | Nenhuma dependência nova necessária (usa `HttpURLConnection` nativo) |
| `AndroidManifest.xml` | Declarar `CatalogActivity` |

---

## Permissões Necessárias

Já declaradas no manifest:
- `android.permission.INTERNET` ✓ (já existe para Discord, covers)

Sem necessidade de permissões adicionais — ROMs são salvas em `getExternalFilesDir()` que não
requer `WRITE_EXTERNAL_STORAGE` em Android 10+.

---

## Dependências Externas

**Nenhuma nova dependência necessária.**

O projeto já usa `HttpURLConnection` nativo para covers e avatars. O mesmo padrão cobre
downloads de ROMs. Não é necessário adicionar OkHttp, Retrofit ou Glide.

---

## Ordem de Implementação Recomendada

```
Etapa 1 — BIOS embarcada (30 min)
  → Colocar .bin em assets/bios/
  → Adicionar extração em DataDirectoryManager ou BootSplashActivity
  → Testar que emulador inicia sem pedir BIOS

Etapa 2 — Manifesto e Parser (1h)
  → Criar catalog_manifest.txt com 5-10 ROMs de teste
  → Implementar CatalogParser.java
  → Testar parse em teste unitário ou log

Etapa 3 — CatalogActivity básica (2h)
  → CatalogEntry.java
  → CatalogAdapter.java com item_catalog.xml
  → CatalogActivity.java mostrando lista sem download
  → Adicionar entrada no drawer menu

Etapa 4 — Download (3h)
  → RomDownloadManager.java
  → dialog_rom_download.xml
  → Integrar dialog em CatalogActivity
  → Testar download completo de uma ROM pequena

Etapa 5 — Integração com lista de jogos (1h)
  → Retorno de resultado para MainActivity
  → refreshGamesList() após download

Etapa 6 — Polish (2h)
  → Cache de capas
  → Busca por título
  → Indicadores visuais de status (baixado/baixando)
  → Tratamento de erros de rede
```

**Total estimado:** ~1 semana de desenvolvimento.

---

## Riscos e Mitigações

| Risco | Impacto | Mitigação |
|-------|---------|-----------|
| ROMs PS2 são grandes (1-10 GB) | Alto — download lento, espaço em disco | Mostrar tamanho antes de confirmar; suporte a resume via `Range` header |
| Servidor de download lento/indisponível | Alto | URL configurável via preferences; fallback para URL alternativa no manifesto |
| BIOS embarcada — questões legais | Alto | Documentar origem legítima; considerar alternativa: usuário importa BIOS própria como onboarding |
| `HttpURLConnection` sem retry automático | Médio | Implementar loop de retry (3 tentativas) com backoff na `RomDownloadManager` |
| ROM baixada não aparece na lista | Médio | `FileObserver` na pasta ou `setResult` + `onActivityResult` |
| CHD de 8GB não cabe no dispositivo | Médio | Verificar espaço livre antes de iniciar download; alertar usuário |
| Download em background morto pelo SO | Médio | Para MVP: não é problema (dialog mantém Activity ativa); versão futura: `DownloadManager` do Android |

---

## Notas Técnicas

### Por que CHD como formato preferido?

`.chd` (Compressed Hunks of Data) é o formato mais eficiente para PS2:
- Gran Turismo 4 `.iso` = ~8 GB → `.chd` ≈ 4-5 GB (tipicamente 40-60% menor)
- PCSX2/ARMSX2 suporta `.chd` nativamente
- Reduz custo de hospedagem e tempo de download do usuário

### Suporte a Resume de Download

Header `Range: bytes=X-` funciona se o servidor suportar HTTP 206 Partial Content.
HuggingFace e a maioria dos CDNs suportam. Implementar verificação da resposta:
- HTTP 206: resume aceito, concatenar ao arquivo existente
- HTTP 200: servidor ignorou Range, recomeçar do zero (deletar `.part` e reabrir em modo write)

### Por que não usar Android DownloadManager?

`DownloadManager` do sistema Android salva em `Downloads/` público e tem menos controle
sobre progresso e retry. O padrão manual via `HttpURLConnection` + Thread (já usado
no projeto para covers) é mais simples e mantém os arquivos em `getExternalFilesDir()`.
Para versões futuras com download em background real, migrar para `WorkManager`.

### Estrutura do Manifesto — Considerações de Escala

Um manifesto com 4000 ROMs PS2, cada linha com ~150 chars = ~600 KB.
Aceitável como asset no APK. Para catálogos maiores futuramente, considerar paginação
ou manifesto baixado na primeira abertura (com cache local).
