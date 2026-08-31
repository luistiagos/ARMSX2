#!/usr/bin/env python3
"""Mede a cobertura de capa 2D do catalogo -- e mede a capa que APARECE, nao o campo preenchido.

    python scripts/check_cover_coverage.py            # usa a rede (baixa os dois indices)
    python scripts/check_cover_coverage.py --offline  # so o que da para saber sem rede
    python scripts/check_cover_coverage.py --min 95   # falha abaixo do piso

Por que nao basta contar linha com URL: as 12.305 linhas do manifesto tem TODAS uma URL, e
mesmo assim 469 nao mostravam capa nenhuma no aparelho. Uma URL pode apontar para um arquivo
que nao existe -- 268 apontavam para um serial ausente do `xlenore/ps2-covers`, 190 para um
nome ausente do `libretro-thumbnails`, 14 para um caminho que o dubsgamer reorganizou. Contar
campo preenchido dava 100%; a cobertura real era 93,4%.

O que este script reproduz e a cadeia de fallback de `GameCoverArt`/`GameInfo.coverUrl` no
modo 2D (o padrao):

    1. a URL do manifesto;
    2. em erro, a arte curada do repo pelo serial (`CatalogSerialIndex` -> covers/default).

Uma entrada conta como coberta se QUALQUER um dos dois responde. A liveness dos dois
repositorios sai de UMA listagem da API do GitHub cada -- nao de 12 mil requisicoes --, e so
as URLs de outros dominios (~2,6 mil unicas) vao para a rede.

Duas contagens, porque respondem perguntas diferentes:

  * por LINHA do manifesto = por arquivo publicado;
  * por CELULA da grade    = por titulo, que e o que o usuario ve (`CatalogParser.groupKey`
    junta as cinco linhas de "007 - Nightfire" numa celula so).

E dentro da celula, a divisao entre JOGO e disco-que-nao-e-jogo: o catalogo carrega discos de
cheat, coletanea de revista, atualizacao de firmware do DESR e desbloqueador de regiao de DVD.
Nenhum deles tem box art em lugar nenhum, e nenhum deles e um jogo -- misturar os dois na mesma
porcentagem esconde a resposta da pergunta que importa.
"""

import argparse
import json
import os
import re
import ssl
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from queue import Empty, Queue

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = os.path.join(ROOT, "catalog_manifest_ps2.txt")
ASSET_MANIFEST = os.path.join(
    ROOT, "platforms", "android", "app", "src", "main", "assets", "catalog_manifest_ps2.txt")
SERIALS = os.path.join(
    ROOT, "platforms", "android", "app", "src", "main", "assets", "catalog_serials.txt")

XL_PREFIX = "https://raw.githubusercontent.com/xlenore/ps2-covers/main/covers/default/"
LR_PREFIX = ("https://raw.githubusercontent.com/libretro-thumbnails/"
             "Sony_-_PlayStation_2/master/Named_Boxarts/")
XL_TREE = "https://api.github.com/repos/xlenore/ps2-covers/git/trees/main"
LR_TREE = ("https://api.github.com/repos/libretro-thumbnails/"
           "Sony_-_PlayStation_2/git/trees/master")
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124 Safari/537.36")

# Discos que o catalogo carrega e que nao sao jogos. Nao ha box art para eles em nenhuma das
# fontes publicas, e conta-los como jogo sem capa afunda a porcentagem sem dizer nada sobre a
# biblioteca. A lista e explicita de proposito: cada termo aqui foi visto no manifesto.
#
# **So conta quando TODAS as variantes do titulo casam** -- ver a montagem das celulas em `main`.
# A primeira versao usava `any` e o resultado era grosseiramente errado: "Ace Combat 04 -
# Shattered Skies" tem tres linhas, uma delas o Taikenban japones, e o jogo inteiro saia da conta
# de jogos por causa da demo. Eram 521 celulas assim classificadas, com jogos de verdade dentro.
#
# Dois termos sairam por serem largos demais para esta lista:
#   * `(Unl)` sozinho -- sem licenca nao e sinonimo de nao-jogo; homebrew e traducao levam a marca;
#   * as edicoes promocionais de montadora (Gran Turismo "Nissan Micra Edition", "Subaru Driving
#     Simulator") -- sao builds jogaveis do GT distribuidas por concessionaria, e jogam.
NON_GAME = re.compile(r"""(?ix)
  action\ ?replay | codebreaker | code\ breaker | gameshark | xploder | \bcheats?\b | codes\ exclusifs
| max\ (drive|play|media|tv|memory|boot) | maxplay | mega\ memory | memory\ card | swap\ magic
| dvd\ (zone|region) | region\ (free|x) | circuit\ breaker | trainer | hot\ \d+\ saves | save\ data
| fantasy\ master | game\ studio | ar[23]\ v | \bar\ max\b | freemcboot | \bulaunchelf\b
| update\ disc | desr- | seizou\ kensa | start-?up\ disc | netfront | \bbrowser\b | \blinux\b
| hdd\ util | multitap | \bfirmware\b | \btest\ disc\b | instant\ messenger | picture\ ?paradise
| dengeki | famitsu | gamepro | electronic\ gaming | \bmagazin | \bmagazine\b | play-pre | \bpsm\b
| special\ issue | best\ ps2\ games | official\ playstation | jampack | \bdemo\b | taikenban
| \btrial\b | otameshi | sampler | \bpromo | preview | lineup | anniversary\ memorial
| premiere\ disc
""")

_PAREN = re.compile(r"\s*\([^)]*\)")
_BRACK = re.compile(r"\s*\[[^\]]*\]")


# --------------------------------------------------------------------------- indices do app

def load_serial_index(path):
    """catalog_serials.txt -> {chave minuscula: serial}, como `CatalogSerialIndex` le."""
    out = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            i = line.find("|")
            if 0 < i < len(line) - 1:
                out[line[:i].strip().lower()] = line[i + 1:].strip().upper()
    return out


def serial_for(index, name):
    """A mesma busca de `CatalogSerialIndex.serialFor`, incluindo os dois fallbacks dela."""
    if not name or not name.strip():
        return None
    clean = name.split("/")[-1].rsplit(".", 1)[0].strip().lower()
    if clean in index:
        return index[clean]
    base = _BRACK.sub("", _PAREN.sub("", clean)).strip()
    if not base:
        return None
    if base in index:
        return index[base]
    alt = base[4:].strip() if base.startswith("the ") else "the " + base
    return index.get(alt)


def base_title(file_name):
    """`CatalogParser.baseTitle`: tira a extensao e os grupos (...) [...] do fim, enquanto houver."""
    i = file_name.rfind(".")
    no_ext = file_name[:i] if i > 0 else file_name
    end = len(no_ext)
    while True:
        while end > 0 and no_ext[end - 1] == " ":
            end -= 1
        if end == 0:
            break
        close = no_ext[end - 1]
        if close == ")":
            opener = "("
        elif close == "]":
            opener = "["
        else:
            break
        start = no_ext.rfind(opener, 0, end - 1)
        if start < 0:
            break
        end = start
    while end > 0 and no_ext[end - 1] in " -_":
        end -= 1
    return no_ext[:end] or no_ext


def group_key(file_name):
    return base_title(file_name).lower()


# ------------------------------------------------------------------------- listagens remotas

def _get_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA,
                                               "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def list_repo_dir(tree_url, path_parts):
    """Os nomes de arquivo de UM diretorio do repo, por travessia de arvore.

    Nao usa `?recursive=1`: o `xlenore/ps2-covers` inteiro estoura o limite da API e volta 500.
    """
    sha = None
    tree = _get_json(tree_url)
    for part in path_parts:
        node = next((x for x in tree["tree"] if x["path"] == part), None)
        if node is None:
            raise RuntimeError("caminho %r ausente no repositorio" % part)
        sha = node["sha"]
        tree = _get_json(tree_url.rsplit("/", 1)[0] + "/" + sha)
    if tree.get("truncated"):
        raise RuntimeError("listagem truncada pela API -- resultado seria subestimado")
    return {x["path"] for x in tree["tree"]}


def probe(url, ctx):
    """HEAD e, se recusado, um GET curto. Devolve True quando a resposta e uma imagem."""
    for method, extra in (("HEAD", {}), ("GET", {"Range": "bytes=0-1023"})):
        try:
            req = urllib.request.Request(
                url, method=method,
                headers={"User-Agent": UA, "Accept": "image/*,*/*", **extra})
            with urllib.request.urlopen(req, timeout=20, context=ctx) as resp:
                if not 200 <= resp.status < 300:
                    return False
                if method == "GET" and not resp.read(1024):
                    return False
                ctype = resp.headers.get("Content-Type", "")
                return not ctype or ctype.startswith("image/") or "octet-stream" in ctype
        except urllib.error.HTTPError as exc:
            if method == "HEAD" and exc.code in (403, 405, 501):
                continue
            return False
        except Exception:
            if method == "HEAD":
                continue
            return False
    return False


def probe_all(urls, threads=24):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    queue = Queue()
    for url in urls:
        queue.put(url)
    result, lock = {}, threading.Lock()

    def worker():
        while True:
            try:
                url = queue.get_nowait()
            except Empty:
                return
            alive = probe(url, ctx)
            with lock:
                result[url] = alive

    workers = [threading.Thread(target=worker, daemon=True) for _ in range(threads)]
    for w in workers:
        w.start()
    for w in workers:
        w.join()
    return result


# ------------------------------------------------------------------------------------ medida

def read_manifest(path):
    rows = []
    with open(path, encoding="utf-8") as fh:
        for n, line in enumerate(fh, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("|")
            file_name = parts[0].strip().split("/")[-1]
            if not file_name:
                continue
            rows.append({"line": n, "file": file_name,
                         "cover": parts[1].strip() if len(parts) > 1 else ""})
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--offline", action="store_true",
                    help="nao toca a rede; so conta o que o serial resolve localmente")
    ap.add_argument("--min", type=float, default=None,
                    help="piso de cobertura de JOGOS por celula; sai 1 abaixo dele")
    ap.add_argument("--list-gaps", action="store_true",
                    help="lista os titulos que continuam sem capa")
    args = ap.parse_args()

    rows = read_manifest(MANIFEST)
    index = load_serial_index(SERIALS)
    for row in rows:
        row["serial"] = serial_for(index, row["file"])
        row["group"] = group_key(row["file"])
    print("manifesto: %d linhas, %d titulos" % (rows and len(rows) or 0,
                                                len({r["group"] for r in rows})))

    if os.path.isfile(ASSET_MANIFEST):
        same = open(MANIFEST, "rb").read() == open(ASSET_MANIFEST, "rb").read()
        print("copia em assets/: %s" % ("identica" if same else "DIVERGENTE -- corrigir"))
        if not same:
            return 1

    if args.offline:
        xl_files = lr_files = None
        net = {}
        print("modo offline: liveness das URLs nao verificada")
    else:
        xl_files = list_repo_dir(XL_TREE, ["covers", "default"])
        lr_files = list_repo_dir(LR_TREE, ["Named_Boxarts"])
        print("indice de capas: %d no ps2-covers, %d no libretro-thumbnails"
              % (len(xl_files), len(lr_files)))
        others = sorted({r["cover"] for r in rows
                         if r["cover"] and not r["cover"].startswith((XL_PREFIX, LR_PREFIX))})
        print("verificando %d URLs de outros dominios..." % len(others))
        net = probe_all(others)

    xl_serials = ({f[:-4].upper() for f in xl_files if f.lower().endswith(".jpg")}
                  if xl_files is not None else None)

    def url_alive(url):
        if not url:
            return False
        if xl_files is None:
            return True  # offline: acredita no campo, e o relatorio avisa
        if url.startswith(XL_PREFIX):
            return url[len(XL_PREFIX):].rsplit(".", 1)[0].upper() in xl_serials
        if url.startswith(LR_PREFIX):
            return urllib.parse.unquote(url[len(LR_PREFIX):]) in lr_files
        return net.get(url, False)

    for row in rows:
        row["url_ok"] = url_alive(row["cover"])
        row["serial_ok"] = bool(xl_serials and row["serial"] and row["serial"] in xl_serials)
        row["covered"] = row["url_ok"] or row["serial_ok"]

    covered = sum(1 for r in rows if r["covered"])
    print("\nPOR LINHA   : %d/%d = %.2f%%   (URL do manifesto viva: %d; so pelo serial: %d)"
          % (covered, len(rows), 100.0 * covered / len(rows),
             sum(1 for r in rows if r["url_ok"]),
             sum(1 for r in rows if not r["url_ok"] and r["serial_ok"])))

    groups = defaultdict(list)
    for row in rows:
        groups[row["group"]].append(row)

    tot = game_tot = game_cov = other_tot = other_cov = 0
    cov_cells = 0
    gaps = []
    for key, variants in groups.items():
        # A celula usa a primeira variante COM campo de capa -- e o que `mergeCatalog` faz.
        pick = next((v for v in variants if v["cover"]), variants[0])
        ok = pick["url_ok"] or pick["serial_ok"]
        tot += 1
        cov_cells += ok
        # `all`, e nao `any`: uma variante demo nao faz do titulo um disco de demonstracao.
        if all(NON_GAME.search(v["file"]) for v in variants):
            other_tot += 1
            other_cov += ok
        else:
            game_tot += 1
            game_cov += ok
            if not ok:
                gaps.append(pick["file"])

    print("POR CELULA  : %d/%d = %.2f%%" % (cov_cells, tot, 100.0 * cov_cells / tot))
    print("  JOGOS     : %d/%d = %.2f%%" % (game_cov, game_tot, 100.0 * game_cov / game_tot))
    print("  nao-jogos : %d/%d = %.2f%%   (discos de cheat, revista, firmware, region-free)"
          % (other_cov, other_tot, 100.0 * other_cov / max(other_tot, 1)))

    if args.list_gaps:
        print("\njogos sem capa 2D (%d):" % len(gaps))
        for name in sorted(gaps):
            print("  " + name)

    if args.min is not None:
        pct = 100.0 * game_cov / game_tot
        if pct < args.min:
            print("\nFALHA: %.2f%% de jogos com capa, abaixo do piso de %.2f%%" % (pct, args.min))
            return 1
        print("\nOK: %.2f%% >= piso de %.2f%%" % (pct, args.min))
    return 0


if __name__ == "__main__":
    sys.exit(main())
