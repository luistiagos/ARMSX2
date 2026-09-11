# -*- coding: utf-8 -*-
"""
Validacao no aparelho da TASK-0090, em uma passagem so.

Existe porque a medicao da TASK-0090 ficou pendente por falta de aparelho, e a proxima sessao
nao deveria ter de re-derivar *como* medir -- so rodar. Cada criterio aqui e o da secao
"Como validar" da task, com o mesmo numero (1a-1d, 2a-2d).

    python docs/task/TASK-0090-validar-no-aparelho.py preflight
    python docs/task/TASK-0090-validar-no-aparelho.py library
    python docs/task/TASK-0090-validar-no-aparelho.py defeito1 --game "/storage/.../Jogo.iso"
    python docs/task/TASK-0090-validar-no-aparelho.py meminfo --label antes
    python docs/task/TASK-0090-validar-no-aparelho.py posmortem
    python docs/task/TASK-0090-validar-no-aparelho.py all --game "/storage/.../Jogo.iso"

O que ele NAO faz, de proposito:

  * Nao escreve nada em `shared_prefs`. Trocar o pref na marra exigiria force-stop + reescrita de
    XML com um JSON escapado dentro, e isso nao pode ser testado sem aparelho -- codigo nao
    exercitado entrando justamente no passo que decide o veredito e como medir com a regua torta.
    O script LE o pref e CONFERE que o estado pedido valeu antes de dar boot; quem troca e o
    operador, na tela.
  * Nao dispara a extracao do Quick Loading. O fluxo passa por um seletor de arquivo do sistema
    (SAF) para escolher o ELF -- `QuickLoadSetup.run(context, iso, elfUri)`. Nao da para conduzir
    isso por adb sem automacao de UI.

!! MEDIDO EM 2026-09-11: NO NOSSO APP O QUICK LOADING NAO TEM PONTO DE ENTRADA. !!
   `QuickLoadSetup.run` nao tem chamador em `src/`: o unico ficava no `ui/home/HomeScreen.kt` do
   upstream, e o merge da TASK-0067 manteve a NOSSA HomeScreen inteira. O menu de toque longo do
   jogo nao oferece "Set up quick loading". Enquanto isso valer, `meminfo` e `posmortem` nao tem
   o que medir -- ninguem consegue disparar a extracao. Eles ficam aqui para quando a entrada
   voltar. Ver a secao Resultado da TASK-0090.

Rodando do Git Bash no Windows: `export MSYS_NO_PATHCONV=1` antes de passar `--game /storage/...`.
Sem isso o MSYS reescreve todo argumento que comeca com `/` como caminho do Windows antes de ele
chegar ao python.exe (so escapam os que tem `[` ou `]`, o que torna a falha intermitente).
  * Nao instala nem desinstala nada. A ordem antes/depois e decisao de quem esta medindo.

Fatos do app conferidos na arvore (nao sao chute):

  * pacote `come.nanodata.armsx2`; a activity exportada e `com.armsx2.BootSplashActivity`
    (`namespace = "com.armsx2"` em app/build.gradle.kts:119) e ela declara um intent-filter
    ACTION_VIEW com `android:scheme="file"` -- AndroidManifest.xml:102-108. Por isso o boot de um
    jogo E automatizavel por `am start -a android.intent.action.VIEW -d file://...`.
  * SharedPreferences: arquivo "ARMSX2" (MainActivityRuntime.kt:2514), logo
    /data/data/come.nanodata.armsx2/shared_prefs/ARMSX2.xml.
  * `ui.sustainedPerf`: boolean, default false (MainActivityRuntime.kt:2670).
  * `affinityMode`: Int dentro do JSON guardado no pref `config.global` (ConfigStore.kt:43 e
    Settings.kt:445, default 7).
  * `romsDirs`: array JSON em string (MainActivityRuntime.kt:2614).
  * marcador do Defeito 1: `@@ANDROID_AFFINITY@@ sustained performance on -> affinity forced to
    Disabled`, impresso com `println` -> sai em System.out no logcat.
  * fim da extracao: `extractIsoToHostfs: wrote N file(s) to <dest>` (native-lib.cpp).
"""

from __future__ import print_function

import argparse
import io
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

PKG = "come.nanodata.armsx2"
COMPONENT = PKG + "/com.armsx2.BootSplashActivity"
PREFS_REL = "shared_prefs/ARMSX2.xml"

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APK_LOCAL = os.path.join(REPO, "platforms", "android", "app", "build", "outputs",
                         "apk", "github", "debug", "app-github-debug.apk")

MARCADOR_AFFINITY = "@@ANDROID_AFFINITY@@ sustained performance on -> affinity forced to Disabled"
MARCADOR_AFFINITY_CURTO = "@@ANDROID_AFFINITY@@"
RE_WROTE = re.compile(r"extractIsoToHostfs: wrote (\d+) file\(s\) to (.+)")

# Um DVD de PS2 passa de 4,7GB; um CD para em 700MB. O corte fica no meio, bem longe dos dois.
DVD_MIN_BYTES = 3 * 1024 ** 3

OUT_DIR = os.path.join(REPO, "docs", "task", "_TASK-0090-medicoes")


# --------------------------------------------------------------------------------------- adb ---

class AdbError(RuntimeError):
    pass


def adb(*args, **kw):
    """Roda adb e devolve stdout ja sem \\r. `check=False` para inspecionar falha sem estourar."""
    check = kw.pop("check", True)
    timeout = kw.pop("timeout", 120)
    cmd = ["adb"] + list(args)
    try:
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        out, err = p.communicate(timeout=timeout)
    except OSError:
        raise AdbError("adb nao encontrado no PATH. Instale o platform-tools ou ajuste o PATH.")
    except subprocess.TimeoutExpired:
        p.kill()
        raise AdbError("adb travou em: %s" % " ".join(cmd))
    out = out.decode("utf-8", "replace").replace("\r\n", "\n")
    err = err.decode("utf-8", "replace").replace("\r\n", "\n")
    if check and p.returncode != 0:
        raise AdbError("adb falhou (%d) em: %s\n%s" % (p.returncode, " ".join(cmd), err.strip()))
    return out


def dispositivos():
    linhas = adb("devices").splitlines()[1:]
    return [l.split("\t")[0] for l in linhas if "\tdevice" in l]


def exige_aparelho():
    d = dispositivos()
    if not d:
        raise AdbError("nenhum aparelho conectado (`adb devices` vazio). Conecte e libere a "
                       "depuracao USB.")
    if len(d) > 1:
        raise AdbError("mais de um aparelho: %s. Deixe so um, ou use ANDROID_SERIAL." % ", ".join(d))
    return d[0]


def modelo():
    return adb("shell", "getprop", "ro.product.model").strip()


# ------------------------------------------------------------------------------------- prefs ---

def le_prefs():
    """O ARMSX2.xml do aparelho, como dict. Exige build debuggable (o githubDebug e)."""
    xml = adb("shell", "run-as", PKG, "cat", PREFS_REL, check=False)
    if not xml.strip().startswith("<?xml"):
        raise AdbError(
            "nao consegui ler %s por run-as.\nSaida: %r\n"
            "run-as so funciona em build debuggable -- confira que o instalado e o githubDebug."
            % (PREFS_REL, xml[:200]))
    d = {}
    root = ET.fromstring(xml)
    for e in root:
        nome = e.get("name")
        if e.tag == "boolean":
            d[nome] = e.get("value") == "true"
        elif e.tag == "string":
            d[nome] = e.text or ""
        elif e.tag in ("int", "long", "float"):
            d[nome] = e.get("value")
    return d


def estado_afinidade(prefs):
    """(sustained, affinityMode). affinityMode vive dentro do JSON de `config.global`."""
    sustained = bool(prefs.get("ui.sustainedPerf", False))
    modo = None
    bruto = prefs.get("config.global")
    if bruto:
        try:
            modo = json.loads(bruto).get("affinityMode")
        except ValueError:
            modo = None
    if modo is None:
        modo = 7  # Settings.kt:445 -- o default quando nada foi gravado ainda.
    return sustained, modo


# ------------------------------------------------------------------------------ APK / "antes" ---

def dex_tem(caminho_apk, agulha):
    alvo = agulha.encode("utf-8")
    with zipfile.ZipFile(caminho_apk) as z:
        for n in z.namelist():
            if n.endswith(".dex") and alvo in z.read(n):
                return True
    return False


def versioncode_instalado():
    txt = adb("shell", "dumpsys", "package", PKG, check=False)
    m = re.search(r"versionCode=(\d+)", txt)
    return m.group(1) if m else "?"


def cmd_preflight(args):
    serial = exige_aparelho()
    print("Aparelho: %s (%s)" % (serial, modelo()))

    caminhos = [l.split(":", 1)[1].strip()
                for l in adb("shell", "pm", "path", PKG, check=False).splitlines()
                if l.startswith("package:")]
    if not caminhos:
        raise AdbError("%s nao esta instalado no aparelho." % PKG)
    base = [c for c in caminhos if c.endswith("base.apk")] or caminhos
    base = base[0]
    tam_inst = adb("shell", "stat", "-c", "%s", base, check=False).strip()
    print("base.apk instalado: %s  (%s bytes, versionCode=%s)"
          % (base, tam_inst or "?", versioncode_instalado()))

    if os.path.isfile(APK_LOCAL):
        print("APK local (o 'depois'): %s bytes" % os.path.getsize(APK_LOCAL))
        print("  %s" % APK_LOCAL)
    else:
        print("APK local AUSENTE em %s -- rode :app:assembleGithubDebug." % APK_LOCAL)

    # A pergunta que decide o Criterio 2a nao e a data nem o versionCode: e se o build instalado
    # JA CONTEM a correcao. Se contem, nao ha par "antes" e 2a fica sem medir -- e nao se inventa
    # um substituto. Da para responder isso direto, olhando o dex do proprio APK instalado.
    os.path.isdir(OUT_DIR) or os.makedirs(OUT_DIR)
    local_copia = os.path.join(OUT_DIR, "base-instalado.apk")
    print("\nPuxando o base.apk instalado para conferir se ele serve de 'antes'...")
    adb("pull", base, local_copia, timeout=600)
    tem = dex_tem(local_copia, MARCADOR_AFFINITY_CURTO)
    print("  marcador '%s' no dex do INSTALADO: %s" % (MARCADOR_AFFINITY_CURTO, "SIM" if tem else "nao"))
    if tem:
        print("\n  >> O build instalado JA TEM a correcao. Ele NAO serve de 'antes'.")
        print("  >> Pela decisao registrada na task, reverter a arvore esta descartado.")
        print("  >> Entao o Criterio 2a fica SEM PAR e deve ser marcado como NAO MEDIDO.")
        print("  >> Os demais criterios (1a-1d, 2b, 2c) seguem mediveis normalmente.")
    else:
        print("\n  >> O build instalado NAO tem a correcao: ele SERVE como 'antes' do Criterio 2a.")
        print("  >> Meca o 'antes' AGORA, com ele, antes de instalar o APK novo:")
        print("  >>     python %s meminfo --label antes" % os.path.relpath(__file__, REPO))
    return 0


# ---------------------------------------------------------------------------------- biblioteca ---

def cmd_library(args):
    exige_aparelho()
    prefs = le_prefs()
    bruto = prefs.get("romsDirs")
    pastas = []
    if bruto:
        try:
            pastas = list(json.loads(bruto))
        except ValueError:
            pastas = []
    if not pastas:
        print("Nenhuma pasta de ROMs no pref 'romsDirs'. Configure a biblioteca no app primeiro.")
        return 1

    print("Pastas de ROMs: %s\n" % ", ".join(pastas))
    achados = []
    for p in pastas:
        if not p.startswith("/"):
            print("  (ignorando %r: nao e caminho de arquivo -- provavelmente SAF content://)" % p)
            continue
        # O `find` do Android e o do toybox, e ele NAO TEM `-printf`: a primeira versao deste
        # script usava `-printf "%s\t%p\n"`, recebia saida vazia e anunciava "nenhuma imagem de
        # disco" num aparelho com quatro ISOs de DVD (2026-09-11). `stat -c` o toybox tem. O filtro
        # de extensao fica no Python, para nao depender de como o shell do aparelho trata `\(`.
        saida = adb("shell", "find '%s' -type f -exec stat -c '%%s|%%n' {} +" % p,
                    check=False, timeout=300)
        for linha in saida.splitlines():
            if "|" not in linha:
                continue
            tam, caminho = linha.split("|", 1)
            if not caminho.lower().endswith((".iso", ".chd", ".cso")):
                continue
            try:
                achados.append((int(tam), caminho.strip()))
            except ValueError:
                pass

    if not achados:
        print("Nenhuma imagem de disco encontrada nas pastas configuradas.")
        return 1

    achados.sort(reverse=True)
    print("%-13s %-10s %s" % ("TAMANHO", "TIPO", "CAMINHO"))
    for tam, caminho in achados:
        gb = tam / float(1024 ** 3)
        # So o .iso tem tamanho que diz se e DVD ou CD. Um .chd/.cso de DVD comprime para a faixa
        # de um CD (Tomb Raider Anniversary: 2,24 GiB em .chd) -- rotular pelo tamanho mentiria.
        if caminho.lower().endswith(".iso"):
            tipo = "DVD" if tam >= DVD_MIN_BYTES else "CD"
        else:
            tipo = "comprimido"
        print("%9.2f GiB %-10s %s" % (gb, tipo, caminho))

    dvds = [(t, c) for t, c in achados if t >= DVD_MIN_BYTES and c.lower().endswith(".iso")]
    print("")
    if dvds:
        alvo = dvds[0]
        print(">> Alvo do Defeito 2: %s (%.2f GB)" % (alvo[1], alvo[0] / float(1024 ** 3)))
        print(">> O Quick Loading exige .iso puro -- .chd/.cso nao servem")
        print("   (I18n 'games.quickLoad.extractFailed').")
        print(">> ATENCAO: tamanho do .iso nao basta. O Criterio 2a so discrimina se o disco tiver")
        print("   VARIOS arquivos medios: o fsync+DONTNEED age por arquivo. Os quatro DVDs do")
        print("   SM-A127M em 2026-09-11 tinham um unico arquivo empacotado de 1,1-4 GB (PART1.PAK,")
        print("   PAC.BIN, DATA.BIN, ROM.) -- durante ele antes e depois rodam o mesmo codigo.")
        print("   Confira tambem /proc/sys/vm/dirty_bytes: la era 100 MB, o que ja impede o 'antes'")
        print("   de acumular GB de pagina suja. Veja a secao Resultado da TASK-0090.")
    else:
        print(">> NAO ha .iso de DVD (>= %.1f GB) neste aparelho." % (DVD_MIN_BYTES / float(1024 ** 3)))
        print(">> O Defeito 2 NAO deve ser medido com um CD: nao enche o page cache o bastante")
        print(">> para o defeito aparecer. Registre 2a-2d como nao medidos por falta de alvo.")
    return 0


# ------------------------------------------------------------------------------------ Defeito 1 ---

def pergunta(texto):
    sys.stdout.write("\n" + texto + "\nEnter para seguir (ou Ctrl+C para abortar)... ")
    sys.stdout.flush()
    try:
        sys.stdin.readline()
    except KeyboardInterrupt:
        raise SystemExit("abortado pelo operador.")


def boot_jogo(caminho):
    adb("shell", "am", "force-stop", PKG, check=False)
    time.sleep(1)
    adb("logcat", "-c", check=False)
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", "file://" + caminho, "-n", COMPONENT, check=False)


def colhe_affinity(espera_s):
    """Espera o boot e devolve as linhas @@ANDROID_AFFINITY@@ vistas."""
    fim = time.time() + espera_s
    linhas = []
    while time.time() < fim:
        time.sleep(3)
        txt = adb("logcat", "-d", "-s", "System.out", check=False)
        linhas = [l for l in txt.splitlines() if MARCADOR_AFFINITY_CURTO in l]
        if linhas:
            break
    return linhas


def rodada(nome, sustained_pedido, modo_pedido, esperado, caminho_jogo, espera_s):
    print("\n" + "=" * 78)
    print("%s -- Sustained Performance = %s, modo de afinidade = %s"
          % (nome, "LIGADO" if sustained_pedido else "DESLIGADO", modo_pedido))
    print("=" * 78)
    pergunta("Na tela: Configuracoes -> Desempenho. Deixe Sustained Performance %s e o modo de "
             "afinidade em %s.\nDepois FECHE as configuracoes."
             % ("LIGADO" if sustained_pedido else "DESLIGADO", modo_pedido))

    sustained, modo = estado_afinidade(le_prefs())
    print("  pref lido do aparelho: ui.sustainedPerf=%s  affinityMode=%s" % (sustained, modo))
    if sustained != sustained_pedido or str(modo) != str(modo_pedido):
        print("  !! O estado no aparelho NAO e o pedido. Ajuste e rode esta rodada de novo.")
        print("  !! (o pref so e gravado quando a tela de configuracoes grava -- feche-a)")
        return (nome, None, "estado errado: sustained=%s modo=%s" % (sustained, modo))

    print("  dando boot em %s ..." % caminho_jogo)
    boot_jogo(caminho_jogo)
    linhas = colhe_affinity(espera_s)
    viu = bool(linhas)
    ok = (viu == esperado)
    print("  linha @@ANDROID_AFFINITY@@: %s (esperado: %s)"
          % ("APARECEU" if viu else "nao apareceu", "aparecer" if esperado else "nao aparecer"))
    for l in linhas:
        print("    | " + l.strip())
    print("  VEREDITO %s: %s" % (nome, "PASSOU" if ok else "REPROVOU"))
    adb("shell", "am", "force-stop", PKG, check=False)
    return (nome, ok, "\n".join(l.strip() for l in linhas))


def cmd_defeito1(args):
    exige_aparelho()
    if not args.game:
        raise SystemExit("--game e obrigatorio (use o subcomando `library` para achar um caminho).")
    r = []
    r.append(rodada("Criterio 1a", True, 7, True, args.game, args.wait))
    r.append(rodada("Criterio 1b", False, 7, False, args.game, args.wait))
    r.append(rodada("Criterio 1c", True, args.numbered, False, args.game, args.wait))

    print("\n" + "=" * 78)
    print("Criterio 1d -- a frase nova na tela, nos dois idiomas")
    print("=" * 78)
    pergunta("Abra Configuracoes -> Desempenho com o app em PORTUGUES e leia a descricao do modo\n"
             "de afinidade. Ela tem de terminar com a frase sobre o Desempenho Sustentado.")
    os.path.isdir(OUT_DIR) or os.makedirs(OUT_DIR)
    for idioma in ("ptbr", "en"):
        if idioma == "en":
            pergunta("Agora troque o idioma do app para INGLES e volte a mesma tela.")
        destino = os.path.join(OUT_DIR, "criterio1d-%s.png" % idioma)
        adb("shell", "screencap", "-p", "/sdcard/_t90.png", check=False)
        adb("pull", "/sdcard/_t90.png", destino, check=False, timeout=120)
        adb("shell", "rm", "-f", "/sdcard/_t90.png", check=False)
        print("  captura: %s" % destino)
    print("  1d e julgado por quem olha a captura -- o script nao decide isso sozinho.")

    print("\n" + "=" * 78)
    print("RESUMO DO DEFEITO 1")
    for nome, ok, detalhe in r:
        print("  %-12s %s" % (nome, "PASSOU" if ok else ("REPROVOU" if ok is False else "NAO MEDIDO")))
        if detalhe:
            print("      %s" % detalhe.replace("\n", "\n      "))
    return 0


# ------------------------------------------------------------------------------------ Defeito 2 ---

def cmd_meminfo(args):
    exige_aparelho()
    os.path.isdir(OUT_DIR) or os.makedirs(OUT_DIR)
    destino = os.path.join(OUT_DIR, "dirty-%s.tsv" % args.label)

    print("Amostragem de /proc/meminfo a cada %.1f s, rotulo '%s'." % (args.intervalo, args.label))
    print("Ela para sozinha quando o log mostrar 'extractIsoToHostfs: wrote N file(s)',")
    print("ou depois de %d s, ou com Ctrl+C.\n" % args.timeout)
    pergunta("1) Prepare o Quick Loading do jogo escolhido (segure a entrada na biblioteca ->\n"
             "   'Set up quick loading' -> confirme -> escolha o ELF).\n"
             "2) Aperte Enter AQUI no instante em que a extracao comecar (a mensagem\n"
             "   'Extracting disc files...' aparecer na tela).")

    adb("logcat", "-c", check=False)
    t0 = time.time()
    serie = []
    escritos = None
    destino_extracao = None
    try:
        while time.time() - t0 < args.timeout:
            txt = adb("shell", "cat", "/proc/meminfo", check=False)
            dirty = wb = None
            for l in txt.splitlines():
                if l.startswith("Dirty:"):
                    dirty = int(l.split()[1])
                elif l.startswith("Writeback:"):
                    wb = int(l.split()[1])
            t = time.time() - t0
            serie.append((t, dirty, wb))
            print("  t=%6.1fs  Dirty=%9s kB  Writeback=%9s kB" % (t, dirty, wb))

            log = adb("logcat", "-d", check=False)
            m = RE_WROTE.search(log)
            if m:
                escritos, destino_extracao = m.group(1), m.group(2).strip()
                print("\n  extracao concluiu: %s arquivo(s) em %s" % (escritos, destino_extracao))
                break
            time.sleep(args.intervalo)
    except KeyboardInterrupt:
        print("\n  interrompido pelo operador.")

    dur = time.time() - t0
    picos = [d for _, d, _ in serie if d is not None]
    with io.open(destino, "w", encoding="utf-8") as f:
        f.write(u"# TASK-0090 Criterio 2a -- rotulo=%s modelo=%s\n" % (args.label, modelo()))
        f.write(u"# duracao_s=%.1f arquivos_escritos=%s destino=%s\n"
                % (dur, escritos, destino_extracao))
        f.write(u"t_s\tDirty_kB\tWriteback_kB\n")
        for t, d, w in serie:
            f.write(u"%.1f\t%s\t%s\n" % (t, d, w))

    print("\n  RESULTADO '%s'" % args.label)
    print("    duracao ate o fim da extracao: %.1f s%s" % (dur, "" if escritos else "  (SEM a linha 'wrote' -- extracao nao concluiu na janela)"))
    if picos:
        print("    Dirty  pico=%d kB (%.0f MB)  mediana=%d kB  final=%d kB"
              % (max(picos), max(picos) / 1024.0, sorted(picos)[len(picos) // 2], picos[-1]))
    print("    arquivos escritos: %s" % escritos)
    print("    serie completa: %s" % destino)
    print("\n  Leitura do criterio 2a: o 'depois' deve ESTABILIZAR na ordem do MAIOR ARQUIVO")
    print("  individual do disco; o 'antes' cresce monotonicamente rumo aos GB da extracao.")
    print("  Sem os dois rotulos nao ha prova -- um 'depois' sozinho nao prova nada.")
    return 0


def cmd_posmortem(args):
    """Criterios 2b e 2c, depois de usar o app ~1 min pos-extracao."""
    exige_aparelho()
    pergunta("Use o app normalmente por ~1 minuto: de boot no jogo pelo ELF extraido, entre no\n"
             "menu, volte. E aqui que o defeito antigo se manifestava. Depois volte e de Enter.")

    log = adb("logcat", "-d", check=False)
    linhas = log.splitlines()

    suspeitas = []
    for l in linhas:
        baixo = l.lower()
        if "lmkd" in baixo and ("not responding" in baixo or "kill" in baixo):
            suspeitas.append(l)
        elif "signal 9" in baixo:
            suspeitas.append(l)
        elif PKG in l and ("died" in baixo or "killed" in baixo):
            suspeitas.append(l)

    print("\nCriterio 2b -- o app foi morto?")
    if suspeitas:
        print("  REPROVOU (ou pelo menos merece leitura). Linhas suspeitas:")
        for l in suspeitas[:40]:
            print("    | " + l.strip())
    else:
        print("  PASSOU: nenhuma linha de lmkd 'device is not responding', 'signal 9' ou morte")
        print("  do processo %s no logcat." % PKG)

    print("\nCriterio 2c -- a extracao continua correta")
    m = RE_WROTE.search(log)
    if m:
        print("  log: extractIsoToHostfs: wrote %s file(s) to %s" % (m.group(1), m.group(2).strip()))
        destino = m.group(2).strip()
        conta = adb("shell", "find", destino, "-type", "f", check=False, timeout=300)
        n = len([x for x in conta.splitlines() if x.strip()])
        print("  arquivos realmente na pasta: %d" % n)
        print("  bate com o log: %s" % ("SIM" if str(n) == m.group(1) else "NAO -- investigue"))
    else:
        print("  a linha 'wrote N file(s)' nao esta no buffer atual do logcat.")
        print("  (o buffer gira; se a extracao foi ha muito tempo, isso nao e um defeito)")
    print("  Falta ainda, a olho: o jogo DA BOOT pelo ELF extraido? Isso e 2c tambem.")
    return 0


# ------------------------------------------------------------------------------------------ all ---

def cmd_all(args):
    for etapa in (cmd_preflight, cmd_library):
        r = etapa(args)
        if r:
            return r
    cmd_defeito1(args)
    cmd_meminfo(args)
    cmd_posmortem(args)
    return 0


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd")
    for nome, fn in (("preflight", cmd_preflight), ("library", cmd_library),
                     ("defeito1", cmd_defeito1), ("meminfo", cmd_meminfo),
                     ("posmortem", cmd_posmortem), ("all", cmd_all)):
        s = sub.add_parser(nome)
        s.set_defaults(fn=fn)
        s.add_argument("--game", default=None, help="caminho do .iso no aparelho")
        s.add_argument("--wait", type=int, default=45, help="segundos de espera pelo boot")
        s.add_argument("--numbered", type=int, default=3, help="modo numerado 1-6 do criterio 1c")
        s.add_argument("--label", default="depois", help="rotulo da serie: antes | depois")
        s.add_argument("--intervalo", type=float, default=2.0, help="segundos entre amostras")
        s.add_argument("--timeout", type=int, default=1800, help="teto da amostragem, em segundos")
    args = p.parse_args()
    if not getattr(args, "fn", None):
        p.print_help()
        return 2
    try:
        return args.fn(args)
    except AdbError as e:
        print("\nERRO: %s" % e)
        return 1


if __name__ == "__main__":
    sys.exit(main())
