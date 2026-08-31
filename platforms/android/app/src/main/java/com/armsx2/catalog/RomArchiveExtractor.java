package com.armsx2.catalog;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;

/**
 * Tira a ROM de dentro de um `.7z` ou `.zip` baixado.
 *
 * Existe porque centenas das 12.628 linhas do manifesto <b>so</b> existem comprimidas na web. A
 * {@link RomDownloadManager} recusava essas fontes desde a TASK-0045 -- baixar 2,3 GB de um
 * arquivo que o emulador nao abre e gastar a franquia do usuario para nada --, e a TASK-0048
 * reabre a porta pagando o preco na outra ponta: aceita o comprimido, e descompacta.
 *
 * Duas decisoes que parecem detalhe e nao sao:
 *
 * <ul>
 *   <li><b>Escreve num arquivo temporario, nunca direto no nome final.</b> Um processo morto no
 *       meio da extracao deixaria um `.iso` truncado no disco, e
 *       {@link CatalogParser#markDownloaded} casa <b>pelo nome sem extensao</b>: a biblioteca
 *       passaria a mostrar como baixado um arquivo pela metade. O temporario e o proprio `.part`
 *       da entrada, que ela ja ignora e que {@link DownloadQueueManager#remove} ja apaga.</li>
 *   <li><b>Renomeia para o nome do manifesto</b> em vez de manter o nome de dentro do arquivo, pelo
 *       mesmo motivo: o nome interno costuma divergir
 *       ({@code 10.000 Bullets (Europe) (En,Fr,De,Es,It).iso} no manifesto,
 *       {@code 10,000 Bullets (Europe).iso} dentro do 7z) e a entrada ficaria eternamente
 *       "nao baixada".</li>
 * </ul>
 */
public final class RomArchiveExtractor {

    private RomArchiveExtractor() {}

    /**
     * O que a extracao vai relatando, e como ela sabe que deve parar.
     *
     * Chamada na thread do download -- quem quiser tocar em UI que reposte para a principal.
     */
    public interface Progress {
        void onProgress(long bytesExtracted, long totalBytes);

        boolean isCancelled();
    }

    /** Cancelamento pedido pelo usuario. Nao e falha: o chamador nao deve reportar erro. */
    public static final class CancelledException extends IOException {
        CancelledException() { super("extracao cancelada"); }
    }

    /**
     * Formatos comprimidos que sabemos abrir.
     *
     * `.rar` <b>nao</b> entra: o commons-compress apenas detecta RAR, nao descompacta. Aceita-lo
     * aqui faria a resolucao escolher uma fonte que a extracao nao consegue abrir -- o download
     * inteiro para terminar em erro.
     */
    private static final String[] ARCHIVE_EXTENSIONS = { "7z", "zip" };

    private static final int BUFFER_SIZE = 65_536; // 64 KB, igual ao do download
    private static final long PROGRESS_THROTTLE_MS = 500;

    /**
     * Folga exigida alem do que o arquivo em si ocupa.
     *
     * Encher o cartao ate o ultimo byte trava o proprio sistema; e o Android reserva parte do que
     * {@code getUsableSpace} informa para si.
     */
    private static final long FREE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024;

    /** true quando a extensao e de um formato comprimido que sabemos abrir. */
    public static boolean isArchive(String extension) {
        for (String e : ARCHIVE_EXTENSIONS) {
            if (e.equals(extension)) return true;
        }
        return false;
    }

    /**
     * Falta de espaco para gravar {@code needed} bytes em {@code dir}, como mensagem -- ou
     * {@code null} quando cabe.
     *
     * Devolve mensagem em vez de lancar de proposito: o laco de download reage a
     * {@link IOException} com tres tentativas e dois {@code sleep}, e disco cheio nao muda de
     * resposta na segunda tentativa.
     */
    public static String spaceShortfall(File dir, long needed) {
        if (needed <= 0) return null;
        long free;
        try {
            free = dir.getUsableSpace();
        } catch (SecurityException e) {
            return null;
        }
        // 0 tambem e o que um sistema de arquivos que nao sabe informar devolve; nao ha o que
        // afirmar nesse caso, e recusar o download seria pior que tentar.
        if (free <= 0) return null;
        long required = needed + FREE_SPACE_MARGIN_BYTES;
        if (free >= required) return null;
        return "Espaco insuficiente: faltam " + megabytes(required - free) + " MB";
    }

    private static String megabytes(long bytes) {
        return String.valueOf((bytes + 1_048_575) / 1_048_576);
    }

    /**
     * Extrai a ROM de {@code archive} e devolve o arquivo pronto para o emulador.
     *
     * Escolhe <b>a maior entrada de dentro cuja extensao o CDVD abre</b> ({@link
     * RomDownloadManager#isPlayable}) -- um 7z de ROM costuma trazer a imagem mais um `readme.txt`
     * ou um `.nfo`, e o tamanho separa os dois sem depender de convencao de nome.
     *
     * No sucesso, {@code archive} e apagado e o resultado fica em {@code destDir} com o nome-base
     * de {@code manifestFileName} e a extensao do que estava dentro.
     *
     * @param tempFile area intermediaria -- o `.part` da entrada. Sobrescrito.
     * @throws CancelledException se {@link Progress#isCancelled()} passar a valer no meio
     * @throws IOException se nao houver dentro nada que o emulador abra, faltar espaco, o formato
     *                     nao for suportado, ou a leitura falhar
     */
    public static File extract(File archive, File destDir, String manifestFileName,
                               File tempFile, Progress progress) throws IOException {
        String extension = RomDownloadManager.extensionOf(archive.getName());
        if ("zip".equals(extension)) return extractZip(archive, destDir, manifestFileName, tempFile, progress);
        if ("7z".equals(extension)) return extractSevenZ(archive, destDir, manifestFileName, tempFile, progress);
        throw new IOException("Formato comprimido nao suportado: ." + extension);
    }

    private static File extractSevenZ(File archive, File destDir, String manifestFileName,
                                      File tempFile, Progress progress) throws IOException {
        try (SevenZFile sevenZ = SevenZFile.builder().setFile(archive).get()) {
            SevenZArchiveEntry best = null;
            for (SevenZArchiveEntry entry : sevenZ.getEntries()) {
                if (entry.isDirectory() || !entry.hasStream()) continue;
                if (!isPlayableEntry(entry.getName())) continue;
                if (best == null || entry.getSize() > best.getSize()) best = entry;
            }
            if (best == null) throw noPlayableEntry(archive);

            requireSpace(destDir, best.getSize());
            try (InputStream in = sevenZ.getInputStream(best)) {
                copy(in, tempFile, best.getSize(), progress);
            }
            return finish(archive, destDir, manifestFileName, best.getName(), tempFile);
        }
    }

    private static File extractZip(File archive, File destDir, String manifestFileName,
                                   File tempFile, Progress progress) throws IOException {
        try (ZipFile zip = ZipFile.builder().setFile(archive).get()) {
            ZipArchiveEntry best = null;
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (!isPlayableEntry(entry.getName())) continue;
                // Metodo de compressao que esta versao nao le (imploded, ppmd em zip antigo):
                // melhor descobrir aqui do que estourar no meio da copia.
                if (!zip.canReadEntryData(entry)) continue;
                if (best == null || entry.getSize() > best.getSize()) best = entry;
            }
            if (best == null) throw noPlayableEntry(archive);

            requireSpace(destDir, best.getSize());
            try (InputStream in = zip.getInputStream(best)) {
                copy(in, tempFile, best.getSize(), progress);
            }
            return finish(archive, destDir, manifestFileName, best.getName(), tempFile);
        }
    }

    /**
     * true quando a entrada de dentro do arquivo tem extensao que o CDVD abre.
     *
     * {@link RomDownloadManager#extensionOf} serve aqui apesar do nome falar de URL: ele corta no
     * ultimo {@code /}, que e tambem o separador de caminho dentro de um 7z ou zip.
     */
    private static boolean isPlayableEntry(String entryName) {
        return RomDownloadManager.isPlayable(RomDownloadManager.extensionOf(entryName));
    }

    private static IOException noPlayableEntry(File archive) {
        return new IOException(
                "Nada que o emulador abra dentro de " + archive.getName());
    }

    private static void requireSpace(File destDir, long needed) throws IOException {
        String shortfall = spaceShortfall(destDir, needed);
        if (shortfall != null) throw new IOException(shortfall);
    }

    private static void copy(InputStream in, File out, long totalBytes, Progress progress)
            throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        long written = 0;
        long lastProgressMs = 0;
        try (FileOutputStream fos = new FileOutputStream(out)) {
            int read;
            while ((read = in.read(buf)) != -1) {
                // Por bloco, e nao por byte: a leitura de um LZMA2 nao e interrompivel por
                // Thread.interrupt, entao esta e a unica granularidade de cancelamento que existe.
                if (progress != null && progress.isCancelled()) throw new CancelledException();
                fos.write(buf, 0, read);
                written += read;
                long now = System.currentTimeMillis();
                if (progress != null && now - lastProgressMs >= PROGRESS_THROTTLE_MS) {
                    lastProgressMs = now;
                    progress.onProgress(written, totalBytes);
                }
            }
            fos.flush();
        }
        if (progress != null) progress.onProgress(written, totalBytes);
    }

    private static File finish(File archive, File destDir, String manifestFileName,
                               String innerName, File tempFile) throws IOException {
        String innerExtension = RomDownloadManager.extensionOf(innerName);
        File finalFile = new File(destDir, RomDownloadManager.withExtension(manifestFileName, innerExtension));
        if (finalFile.exists()) finalFile.delete();
        if (!tempFile.renameTo(finalFile)) {
            throw new IOException("Nao foi possivel gravar " + finalFile.getName());
        }
        // So agora: enquanto o rename nao aconteceu, o comprimido e a unica copia dos bytes que o
        // usuario baixou.
        archive.delete();
        return finalFile;
    }
}
