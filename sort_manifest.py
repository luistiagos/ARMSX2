"""Reordena o catalogo de ROMs preservando o bloco curado de capas.

    python sort_manifest.py

Le `catalog_manifest_ps2.txt` na raiz (a fonte), reordena e copia para
`app/src/main/assets/catalog_manifest_ps2.txt` (o que vai no APK).

## Por que este script mudou

A versao anterior ordenava por `(tem_URL, nome)`: tudo que tinha capa ia para o
topo, o resto para o fundo. Isso NAO reproduz o arquivo que esta em uso, e
rodar aquela versao hoje reordenaria as 12.628 linhas inteiras, desfazendo em
silencio uma curadoria feita a mao.

O arquivo em uso tem dois blocos, cada um alfabetico:

  Bloco 1 (1..1780)  entradas com capa: 1779 `.chd` com URL, mais UM `.iso`
                     promovido a mao ("PS2-Super Bomba Patch 2026").
  Bloco 2 (1781..)   todo o resto -- `.chd` sem capa, `.iso`, `.7z` -- com e
                     sem URL misturados.

Repare no `.iso` promovido: ele prova que o bloco 1 NAO e derivavel de uma
regra pura sobre o conteudo da linha. Alguem decidiu que aquela entrada merece
aparecer entre as com capa apesar de nao ser `.chd`. Uma regra automatica
apagaria essa decisao.

Por isso o script agora **aprende** a curadoria do proprio arquivo: o conjunto
de caminhos que hoje esta no bloco 1 continua no bloco 1. Acrescentar uma
entrada nova com URL a coloca no bloco 1 se for `.chd`; para promover um
`.iso`, basta coloca-lo no bloco 1 a mao uma vez -- a proxima execucao respeita.
"""

import os
import shutil
import sys

ROOT_MANIFEST = 'catalog_manifest_ps2.txt'
ASSETS_MANIFEST = os.path.join('app', 'src', 'main', 'assets', 'catalog_manifest_ps2.txt')


def path_of(line):
    return line.split('|', 1)[0]


def has_cover(line):
    return '|' in line and line.split('|', 1)[1].strip() != ''


def read_lines(filename):
    # newline='' para nao traduzir; a normalizacao e feita a seguir, uma vez so.
    with open(filename, 'r', encoding='utf-8', newline='') as fh:
        raw = fh.read()
    return [ln for ln in raw.replace('\r\n', '\n').split('\n') if ln.strip()]


def learn_promoted(lines):
    """Caminhos que ja estao no bloco de capas sem serem `.chd`.

    Sao as promocoes manuais. Detectadas como: entradas com capa que aparecem
    ANTES da primeira entrada sem capa. Se o arquivo ainda nao tem os dois
    blocos (primeira execucao numa lista nova), devolve conjunto vazio e o
    resultado e a regra pura.
    """
    promoted = set()
    for line in lines:
        if not has_cover(line):
            break
        if not path_of(line).lower().endswith('.chd'):
            promoted.add(path_of(line))
    return promoted


def sort_key(promoted):
    def key(line):
        path = path_of(line)
        in_cover_block = has_cover(line) and (path.lower().endswith('.chd') or path in promoted)
        return (0 if in_cover_block else 1, path.lower())
    return key


def main():
    if not os.path.exists(ROOT_MANIFEST):
        print("ERRO: '%s' nao foi encontrado na raiz do projeto." % ROOT_MANIFEST)
        return 1

    lines = read_lines(ROOT_MANIFEST)
    promoted = learn_promoted(lines)
    ordered = sorted(lines, key=sort_key(promoted))

    cover_block = sum(1 for ln in ordered if not sort_key(promoted)(ln)[0])
    changed = ordered != lines

    # newline='\n' de proposito: em modo texto o Windows escreveria CRLF, e o
    # arquivo versionado esta em LF. Sem isto, toda execucao produz um diff de
    # 12.628 linhas que nao muda uma virgula do conteudo.
    with open(ROOT_MANIFEST, 'w', encoding='utf-8', newline='\n') as fh:
        for line in ordered:
            fh.write(line + '\n')

    print("'%s': %d entradas, %d no bloco de capas (%d promocoes manuais)."
          % (ROOT_MANIFEST, len(ordered), cover_block, len(promoted)))
    print('  ordem %s.' % ('ALTERADA' if changed else 'ja estava correta'))

    if os.path.isdir(os.path.dirname(ASSETS_MANIFEST)):
        shutil.copy2(ROOT_MANIFEST, ASSETS_MANIFEST)
        print("  copiado para '%s'." % ASSETS_MANIFEST)
    else:
        print("AVISO: '%s' nao existe; copia para assets ignorada."
              % os.path.dirname(ASSETS_MANIFEST))
    return 0


if __name__ == '__main__':
    sys.exit(main())
