package com.armsx2.catalog;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CatalogParser {

    private static final String MANIFEST_ASSET = "catalog_manifest_ps2.txt";

    private CatalogParser() {}

    /**
     * Lê catalog_manifest_ps2.txt dos assets e retorna lista de entradas.
     *
     * Formatos suportados (uma ROM por linha):
     *   ps2/filename.chd                                            (só caminho)
     *   ps2/filename.chd|cover_url                                  (caminho + capa)
     *   ps2/filename.iso|download_url|cover_url                     (caminho + download direto + capa)
     *   ps2/filename.chd|Título|cover_url                           (caminho + título + capa)
     *   ps2/filename.chd|Título|cover_url|download_url              (formato completo 4 campos)
     *
     * Linhas começando com '#' ou vazias são ignoradas.
     */
    public static List<CatalogEntry> parse(Context context) {
        try (InputStream is = context.getAssets().open(MANIFEST_ASSET);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            return parseFromReader(reader);
        } catch (IOException e) {
            // Manifesto não encontrado ou corrompido — retorna lista vazia
            return new ArrayList<>();
        }
    }

    public static List<CatalogEntry> parseFromReader(BufferedReader reader) throws IOException {
        List<CatalogEntry> entries = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            CatalogEntry entry = parseLine(line);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    public static CatalogEntry parseLine(String line) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) return null;

        String[] parts = line.split("\\|", -1);

        // Extrai fileName do path "ps2/filename.chd"
        String rawPath = parts[0].trim();
        String fileName = rawPath.contains("/")
                ? rawPath.substring(rawPath.lastIndexOf('/') + 1)
                : rawPath;

        if (fileName.isEmpty()) return null;

        String title = stripExtension(fileName);
        String coverUrl = "";
        String downloadUrl = "";

        if (parts.length == 1) {
            // Format: path
        } else if (parts.length == 2) {
            String p1 = parts[1].trim();
            if (isHttpUrl(p1)) {
                if (isDownloadUrl(p1)) {
                    downloadUrl = p1;
                } else {
                    coverUrl = p1;
                }
            } else if (!p1.isEmpty()) {
                title = p1;
            }
        } else if (parts.length == 3) {
            String p1 = parts[1].trim();
            String p2 = parts[2].trim();
            if (isHttpUrl(p1) && isHttpUrl(p2)) {
                // Two URLs: differentiate cover image and ROM download
                if (isDownloadUrl(p2) || isImageUrl(p1)) {
                    coverUrl = p1;
                    downloadUrl = p2;
                } else if (isDownloadUrl(p1) || isImageUrl(p2)) {
                    downloadUrl = p1;
                    coverUrl = p2;
                } else {
                    coverUrl = p1;
                    downloadUrl = p2;
                }
            } else if (isHttpUrl(p1)) {
                if (isDownloadUrl(p1)) {
                    downloadUrl = p1;
                } else {
                    coverUrl = p1;
                }
                if (!p2.isEmpty()) title = p2;
            } else {
                // Format: path|title|cover_url or download_url
                if (!p1.isEmpty()) title = p1;
                if (isDownloadUrl(p2)) {
                    downloadUrl = p2;
                } else {
                    coverUrl = p2;
                }
            }
        } else {
            // Format: path|title|cover_url|download_url
            String p1 = parts[1].trim();
            String p2 = parts[2].trim();
            String p3 = parts[3].trim();
            if (!p1.isEmpty() && !isHttpUrl(p1)) {
                title = p1;
            }
            if (isHttpUrl(p2) && isHttpUrl(p3)) {
                if (isDownloadUrl(p2) || isImageUrl(p3)) {
                    downloadUrl = p2;
                    coverUrl = p3;
                } else {
                    coverUrl = p2;
                    downloadUrl = p3;
                }
            } else {
                coverUrl = p2;
                downloadUrl = p3;
            }
        }

        // Safety fallback: title should never be an HTTP URL
        if (isHttpUrl(title)) {
            title = stripExtension(fileName);
        }

        return new CatalogEntry(fileName, title, coverUrl, downloadUrl);
    }

    private static boolean isHttpUrl(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean isImageUrl(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.contains("/images.igdb.com/")
                || lower.contains("libretro-thumbnails") || lower.contains("/covers/")
                || lower.contains("boxart");
    }

    private static boolean isDownloadUrl(String s) {
        if (s == null) return false;
        if (isImageUrl(s)) return false;
        String lower = s.toLowerCase();
        return lower.endsWith(".iso") || lower.endsWith(".chd") || lower.endsWith(".bin")
                || lower.endsWith(".cso") || lower.endsWith(".gz") || lower.endsWith(".zip")
                || lower.endsWith(".7z") || lower.endsWith(".rar") || lower.contains("/resolve/")
                || lower.contains("archive.org/download");
    }

    /**
     * Marca quais entradas já têm ROM baixada (arquivo existe e tamanho &gt; 0).
     *
     * O casamento é pelo nome <b>sem extensão</b>, e não pelo nome inteiro: uma linha
     * {@code .iso} do manifesto pode ter sido baixada como {@code .chd}, porque é o formato que a
     * fonte tinha e é a extensão que decide o leitor do CDVD (ver
     * {@link RomDownloadManager#localFileName}). Comparando o nome cheio, esse jogo ficaria
     * eternamente "não baixado" — e apareceria duas vezes na grade, uma como arquivo solto e
     * outra como linha de catálogo.
     *
     * Uma leitura de diretório em vez de um {@code exists()} por entrada: são 12.628 linhas no
     * manifesto, e a varredura roda a cada abertura da biblioteca.
     */
    public static void markDownloaded(List<CatalogEntry> entries, File romsDir) {
        Map<String, Long> present = new HashMap<>();
        File[] files = (romsDir == null) ? null : romsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                // Um download em curso não conta como baixado — nem ele nem a anotação de
                // origem que anda ao lado dele.
                if (name.endsWith(".part") || name.endsWith(".part.src")) continue;
                present.put(stripExtension(name).toLowerCase(), f.length());
            }
        }
        for (CatalogEntry entry : entries) {
            Long size = present.get(stripExtension(entry.fileName).toLowerCase());
            entry.isDownloaded = size != null && size > 0L;
            if (!entry.isDownloaded && entry.queueState == DownloadQueueManager.State.DONE) {
                entry.queueState = null;
            }
        }
    }

    private static String stripExtension(String name) {
        int i = name.lastIndexOf('.');
        return (i > 0) ? name.substring(0, i) : name;
    }

    /**
     * O titulo do jogo, sem o que distingue um lancamento do outro: extensao fora, e os grupos
     * entre parenteses/colchetes do fim removidos enquanto houver.
     *
     * <pre>
     *   God of War (USA).chd                -> "God of War"
     *   God of War (Europe) (En,Fr,De).chd  -> "God of War"
     *   Black (Legendado PT-BR) [PS2].iso   -> "Black"
     * </pre>
     *
     * E a chave que faz 12.628 linhas de manifesto virarem 6.569 celulas na biblioteca
     * (TASK-0047). A regra e posicional em vez de uma lista de sufixos conhecidos porque os 639
     * grupos distintos do manifesto sao <b>todos</b> metadado -- regiao, idioma, {@code (v1.03)},
     * {@code (Disc 1)}, {@code (Shokai Genteiban)} --, nenhum e parte do nome de um jogo, e uma
     * lista ficaria desatualizada na proxima entrada acrescentada.
     *
     * Nunca devolve vazio: um nome que e so um sufixo ({@code "(USA).chd"}) volta inteiro, senao
     * viraria uma chave vazia agrupando titulos sem relacao nenhuma.
     */
    public static String baseTitle(String fileName) {
        String noExt = stripExtension(fileName);
        int end = noExt.length();
        while (true) {
            while (end > 0 && noExt.charAt(end - 1) == ' ') end--;
            if (end == 0) break;
            char close = noExt.charAt(end - 1);
            char open;
            if (close == ')') open = '(';
            else if (close == ']') open = '[';
            else break;
            int start = noExt.lastIndexOf(open, end - 2);
            if (start < 0) break;
            end = start;
        }
        // Sobras de separador entre o nome e o primeiro sufixo removido.
        while (end > 0) {
            char c = noExt.charAt(end - 1);
            if (c == ' ' || c == '-' || c == '_') end--;
            else break;
        }
        String base = noExt.substring(0, end);
        return base.isEmpty() ? noExt : base;
    }

    /** A chave de agrupamento de uma entrada: {@link #baseTitle} normalizado para comparacao. */
    public static String groupKey(String fileName) {
        return baseTitle(fileName).toLowerCase();
    }
}
