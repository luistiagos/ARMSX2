#!/usr/bin/env python3
"""Valida a rastreabilidade feature <-> task <-> bug descrita em docs/README.md.

Rode antes de todo push:

    python scripts/check_traceability.py
    python scripts/check_traceability.py --fix   # completa o indice de docs/task/README.md

Sai com codigo 1 e lista os problemas quando algo nao fecha. O que ele checa e
estrutural -- se um link declarado de um lado existe do outro, se uma task
concluida aponta para um commit que existe. Se a task DESCREVE honestamente o
que o commit fez, isso nenhum script verifica.
"""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TASK_DIR = os.path.join(ROOT, "docs", "task")
FEAT_DIR = os.path.join(ROOT, "docs", "features")
BUG_ROOT = os.path.join(ROOT, "docs", "bugs")
BUG_DIRS = [os.path.join(BUG_ROOT, "open"),
            os.path.join(BUG_ROOT, "done")]

TASK_RE = re.compile(r"^TASK-(\d{4})-.+\.md$")
FEAT_RE = re.compile(r"^FEAT-(\d{4})-.+\.md$")
TASK_ID_RE = re.compile(r"TASK-\d{4}")
FEAT_ID_RE = re.compile(r"FEAT-\d{4}")

# Linha de tabela markdown que comeca por um link de task: `| [TASK-0010](...) | ... |`
TABLE_TASK_ROW_RE = re.compile(r"^\|\s*\[(TASK-\d{4})\]")

TASK_STATUSES = {"aberta", "em andamento", "concluída", "revertida"}
FEAT_STATUSES = {"planejada", "em andamento", "concluída", "abandonada"}

problems = []


def fail(path, msg):
    problems.append("%s: %s" % (os.path.relpath(path, ROOT).replace("\\", "/"), msg))


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def field(text, name):
    """Le um campo do cabecalho no formato '- **Nome:** valor'.

    O valor pode CONTINUAR nas linhas seguintes enquanto elas vierem indentadas -- e como a
    TASK-0045 lista os dois bugs que resolve. Ler so a primeira linha escondia o segundo link e
    fazia o bug parecer orfao de task."""
    pattern = re.compile(r"^-\s+\*\*%s:\*\*\s*(.*)$" % re.escape(name))
    lines = text.splitlines()
    for i, line in enumerate(lines):
        m = pattern.match(line)
        if not m:
            continue
        parts = [m.group(1).strip()]
        for cont in lines[i + 1:]:
            if not cont.strip() or not cont[:1].isspace():
                break
            parts.append(cont.strip())
        return " ".join(p for p in parts if p).strip()
    return None


EMPTY_VALUES = ("", "—", "-", "nenhum", "nenhuma", "PENDENTE")


def is_empty(value):
    """Um comentario entre parenteses depois do travessao NAO torna o campo preenchido.

    Metade dos campos vazios do repositorio e escrita assim -- `— (nao altera o aplicativo)`,
    `— (preencher via --amend antes do push)` -- e le-los como valor fazia, por exemplo, a
    TASK-0075 (`— (o publicador existe, mas nada foi publicado)`) virar fronteira de publicacao
    e reprovar quatro tasks que nunca foram ao ar."""
    if value is None:
        return True
    stripped = re.sub(r"\s*\([^()]*\)\s*$", "", value.strip()).strip()
    return stripped in EMPTY_VALUES


def declares_nothing(value):
    """O campo NEGA o vinculo em vez de o declarar.

    `**nenhuma** — a TASK-0065 registra o defeito e **não o corrige**` cita uma task de proposito
    para dizer que ela NAO resolve o bug. Exigir backlink dai transformaria a explicacao num erro,
    e a saida seria apagar a explicacao -- exatamente a informacao que vale."""
    if value is None:
        return True
    bare = value.strip().strip("*").strip()
    return is_empty(bare) or bare.lower().startswith(("nenhum", "nenhuma", "—", "-"))


def git(*args):
    try:
        return subprocess.check_output(["git"] + list(args), cwd=ROOT,
                                       stderr=subprocess.DEVNULL).decode("utf-8", "replace").strip()
    except (subprocess.CalledProcessError, OSError):
        return None


def is_ancestor(sha, of):
    try:
        subprocess.check_call(["git", "merge-base", "--is-ancestor", sha, of],
                              cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except (subprocess.CalledProcessError, OSError):
        return False


def commit_is_reachable(sha):
    """Existir no banco de objetos nao basta: um commit orfao (pos --amend, pos rebase)
    ainda responde a cat-file e mentiria sobre o historico. Tem de ser ancestral de HEAD."""
    if git("cat-file", "-e", sha + "^{commit}") is None:
        return False
    return is_ancestor(sha, "HEAD")


_LOG_CACHE = {}


def commit_log(rev):
    """[(hash, assunto)] de `rev`, lido uma vez so.

    `%s` e o ASSUNTO -- a primeira linha da mensagem -- e nada mais. E o que faz a comparacao em
    `commits_for_task` ser sobre o assunto de verdade, e nao sobre a mensagem inteira."""
    if rev not in _LOG_CACHE:
        out = git("log", "--format=%h%x00%s", rev)
        rows = []
        for line in (out or "").splitlines():
            if "\0" in line:
                h, s = line.split("\0", 1)
                rows.append((h, s))
        _LOG_CACHE[rev] = rows
    return _LOG_CACHE[rev]


def commits_for_task(tid, reachable_only=False):
    """Fonte de verdade do vinculo task->commit: o assunto do commit. Git nao mente.

    O filtro e feito aqui, em Python, sobre `%s`. NAO com `git log --grep`: aquilo aplica a
    expressao a MENSAGEM INTEIRA, e `^` casa o inicio de qualquer linha do corpo -- entao um
    `chore:` que mencionasse `TASK-NNNN:` numa linha do corpo era contado como o commit da task.
    Ver o bug `checktraceability-grep-casa-corpo-do-commit`.

    Procura em `--all`, nao em `HEAD`. O motivo e o fork: a branch do fork nasce da arvore do
    upstream e portanto NAO alcanca os commits da linha anterior do produto. Com `HEAD`, toda task
    concluida antes do fork passaria a reprovar aqui -- o registro mentiria sobre trabalho que
    existe e esta no repositorio, so que noutro ramo.

    Isto NAO enfraquece a checagem de commit orfao acima. Aquela existe para hash escrito a mao no
    campo `Commit:` de uma task, e um orfao de `--amend` nao e alcancavel por nenhuma ref --
    portanto `--all` continua sem o enxergar.

    `reachable_only` restringe a HEAD, e existe porque os NUMEROS DE TASK NAO SAO UNICOS ENTRE
    RAMOS: `feature/fork-upstream-android` e `feature/handoff-end-to-end` nao tem historia comum e
    numeraram tasks em paralelo, entao ha uma TASK-0016 em cada uma, com assuntos completamente
    diferentes. Para VALIDAR ("existe commit?") a busca larga e desejavel -- e o que evita reprovar
    task anterior ao fork. Para ESCREVER O INDICE deste worktree, nao: gravar o hash do outro ramo
    e registrar mentira. Ver o bug `numeros-de-task-colidem-entre-ramos`.
    """
    prefix = tid + ":"
    rev = "HEAD" if reachable_only else "--all"
    return [h for h, s in commit_log(rev) if s.startswith(prefix)]


def md_files(directory, pattern):
    if not os.path.isdir(directory):
        return []
    return sorted(f for f in os.listdir(directory) if pattern.match(f))


def bug_files():
    """Todo relatorio de bug do repositorio, em caminho absoluto. As pastas tem subpastas
    (`open/armsx2-fork/`, `open/legado-version1/`), entao a varredura e recursiva."""
    out = []
    for base in BUG_DIRS:
        for dirpath, _dirs, names in os.walk(base):
            for name in sorted(names):
                if name.endswith(".md") and name != "README.md":
                    out.append(os.path.join(dirpath, name))
    return sorted(out)


def linked_bug_paths(task_path, text):
    """Caminhos absolutos dos bugs que a task declara resolver."""
    value = field(text, "Bugs que resolve") or ""
    return {os.path.normpath(os.path.join(os.path.dirname(task_path), link))
            for link in re.findall(r"\]\(([^)]+\.md)\)", value)}


def split_row(line):
    """Celulas de uma linha de tabela markdown, sem os pipes das pontas."""
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    return s.split("|")


def table_task_rows(text):
    """[(tid, status)] das linhas de tabela que comecam por um link de task.

    Restringe a varredura a TABELA. `TASK_ID_RE.findall(text)` varria a prosa inteira, e uma
    frase sobre trabalho futuro citando uma task ainda nao escrita fazia o validador exigir o
    arquivo dela."""
    rows = []
    for line in text.splitlines():
        m = TABLE_TASK_ROW_RE.match(line)
        if not m:
            continue
        cells = split_row(line)
        rows.append((m.group(1), cells[1].strip() if len(cells) > 1 else ""))
    return rows


def main():
    tasks = {}   # TASK-NNNN -> (path, text)
    feats = {}   # FEAT-NNNN -> (path, text)

    for name in md_files(TASK_DIR, TASK_RE):
        path = os.path.join(TASK_DIR, name)
        tasks["TASK-" + TASK_RE.match(name).group(1)] = (path, read(path))

    for name in md_files(FEAT_DIR, FEAT_RE):
        path = os.path.join(FEAT_DIR, name)
        feats["FEAT-" + FEAT_RE.match(name).group(1)] = (path, read(path))

    if not tasks:
        print("Nenhuma task encontrada em docs/task/ -- nada a validar.")
        return 0

    # ---- tasks -------------------------------------------------------------
    for tid, (path, text) in sorted(tasks.items()):
        if not text.lstrip().startswith("# " + tid + ":"):
            fail(path, "o titulo deve comecar com '# %s: '" % tid)

        status = field(text, "Status")
        if status not in TASK_STATUSES:
            fail(path, "Status %r invalido (use: %s)" % (status, ", ".join(sorted(TASK_STATUSES))))

        for required in ("Criada em", "Feature", "Bugs que resolve", "Commit"):
            if field(text, required) is None:
                fail(path, "campo obrigatorio ausente: **%s**" % required)

        # O vinculo task->commit e verificado no GIT, nao no texto: o assunto do commit tem
        # de comecar com "TASK-NNNN:". Isso evita o problema circular de gravar dentro de um
        # commit o hash que so existe depois dele.
        # Uma task pode ter MAIS de um commit. Ja teve de ter exatamente um, e a regra cobrava caro:
        # qualquer retorno a uma task ja commitada obrigava a `--amend`, que reescreve o historico --
        # o mesmo estrago que `commit_is_reachable` existe para detectar. O que importa continua
        # verificado: task concluida tem de ter ao menos um commit com o assunto.
        if status == "concluída":
            if not commits_for_task(tid):
                fail(path, "status 'concluída' mas nenhum commit alcancavel tem assunto '%s: ...'" % tid)

        # Se o campo Commit tiver um hash escrito a mao, ele tem de bater com o git.
        commit = field(text, "Commit")
        if commit and not is_empty(commit):
            sha = commit.strip().strip("`")
            if re.fullmatch(r"[0-9a-f]{7,40}", sha):
                if not commit_is_reachable(sha):
                    fail(path, "commit %r nao e alcancavel a partir de HEAD "
                               "(orfao de --amend/rebase?)" % sha)

        if status == "revertida" and is_empty(field(text, "Revertida por")):
            fail(path, "status 'revertida' exige **Revertida por**")

        # task -> feature, e a feature tem de listar a task de volta
        feat_value = field(text, "Feature") or ""
        for fid in set(FEAT_ID_RE.findall(feat_value)):
            if fid not in feats:
                fail(path, "aponta para %s, que nao existe em docs/features/" % fid)
            elif tid not in feats[fid][1]:
                fail(feats[fid][0], "nao lista %s, que declara pertencer a ela" % tid)

        # task -> bug, e o bug tem de citar a task de volta
        for bug_path in sorted(linked_bug_paths(path, text)):
            if not os.path.isfile(bug_path):
                fail(path, "bug referenciado nao existe: %s"
                           % os.path.relpath(bug_path, ROOT).replace("\\", "/"))
            elif tid not in read(bug_path):
                fail(bug_path, "nao cita %s, que declara resolve-lo" % tid)

    check_published(tasks)

    # ---- features ----------------------------------------------------------
    for fid, (path, text) in sorted(feats.items()):
        status = field(text, "Status")
        if status not in FEAT_STATUSES:
            fail(path, "Status %r invalido (use: %s)" % (status, ", ".join(sorted(FEAT_STATUSES))))

        for tid, row_status in table_task_rows(text):
            if tid not in tasks:
                fail(path, "lista %s, que nao existe em docs/task/" % tid)
                continue
            if fid not in tasks[tid][1]:
                fail(tasks[tid][0], "nao aponta de volta para %s, que a lista" % fid)
            # Status cruzado: a feature nao pode descrever a task de um jeito e a task de outro.
            # Caso real: a FEAT-0001 listou a TASK-0009 como `aberta` depois de publicada.
            real_status = field(tasks[tid][1], "Status")
            if row_status and real_status and row_status != real_status:
                fail(path, "lista %s como %r, mas a task declara %r"
                           % (tid, row_status, real_status))

    # ---- bugs --------------------------------------------------------------
    check_bugs(tasks)

    if "--fix" in sys.argv:
        fill_index(tasks)

    check_index(tasks)

    if problems:
        print("Rastreabilidade REPROVADA -- %d problema(s):\n" % len(problems))
        for p in problems:
            print("  - " + p)
        return 1

    print("OK -- %d task(s), %d feature(s), rastreabilidade consistente."
          % (len(tasks), len(feats)))
    return 0


def check_bugs(tasks):
    """bug -> task, o sentido que faltava. Ate aqui so `task -> bug` era conferido, entao um
    relatorio podia declarar-se resolvido por uma task que nunca ouviu falar dele."""
    done_dir = BUG_DIRS[1]
    linked = {tid: linked_bug_paths(path, text) for tid, (path, text) in tasks.items()}

    for path in bug_files():
        text = read(path)
        value = field(text, "Tasks que o resolvem")

        for tid in sorted(set(TASK_ID_RE.findall("" if declares_nothing(value) else value))):
            if tid not in tasks:
                fail(path, "declara ser resolvido por %s, que nao existe em docs/task/" % tid)
            elif os.path.normpath(path) not in linked[tid]:
                fail(tasks[tid][0], "nao lista em **Bugs que resolve** o bug %s, "
                                    "que declara ser resolvido por ela"
                                    % os.path.relpath(path, BUG_ROOT).replace("\\", "/"))

        # Bug fechado tem de DECLARAR a task no campo, nao mencionar "TASK-" em prosa qualquer:
        # uma frase como "hipotese eliminada pela TASK-0008" satisfazia o check antigo.
        if os.path.dirname(path).startswith(done_dir):
            if not TASK_ID_RE.search(value or "") and "sem-task-legado" not in text:
                fail(path, "bug em done/ sem task declarada em **Tasks que o resolvem** "
                           "(marque 'sem-task-legado' se for anterior ao sistema)")


def check_published(tasks):
    """Task concluida cujo commit ja foi ao ar nao pode ficar com **Publicado em:** vazio.

    Quem define "ja foi ao ar" e o proprio registro: os commits das tasks que DECLARAM um
    `Publicado em` sao a fronteira de publicacao, e tudo que e ancestral deles ja chegou ao
    cliente. Nao ha convencao nova a manter -- preencher uma task passa a exigir as anteriores,
    que e exatamente o comportamento desejado."""
    frontier = []
    for tid, (_path, text) in tasks.items():
        if is_empty(field(text, "Publicado em")):
            continue
        frontier.extend(commits_for_task(tid, reachable_only=True))
    if not frontier:
        return
    for tid, (path, text) in sorted(tasks.items()):
        if field(text, "Status") != "concluída" or not is_empty(field(text, "Publicado em")):
            continue
        shas = commits_for_task(tid, reachable_only=True)
        if shas and all(any(is_ancestor(s, f) for f in frontier) for s in shas):
            fail(path, "concluída e ja publicada (commit ancestral de uma task com "
                       "**Publicado em**), mas o campo **Publicado em** esta vazio")


# ---- indice de docs/task/README.md ----------------------------------------

def index_path():
    return os.path.join(TASK_DIR, "README.md")


def index_columns(lines):
    """(indice da linha do cabecalho, {nome da coluna: posicao}) da tabela do indice.

    Existe para o script parar de assumir que `Commit` e a ULTIMA coluna -- o regex antigo
    assumia isso em silencio, e a assuncao ja produziu linhas com o hash gravado na coluna
    `Feature`."""
    for i, line in enumerate(lines):
        if not line.strip().startswith("|"):
            continue
        cells = [c.strip() for c in split_row(line)]
        if "Task" in cells and "Commit" in cells:
            return i, {name: j for j, name in enumerate(cells)}
    return None, {}


def task_title(tid, text):
    m = re.search(r"^#\s+" + tid + r":\s*(.+?)\s*$", text, re.MULTILINE)
    # `|` num titulo quebraria a tabela; `\|` e o escape do markdown.
    return m.group(1).replace("|", r"\|") if m else ""


def bug_slugs(task_path, text):
    """Nome do relatorio sem o carimbo de data -- e o que as linhas existentes ja usam."""
    slugs = []
    for p in sorted(linked_bug_paths(task_path, text)):
        name = os.path.basename(p)
        name = re.sub(r"_\d{4}-\d{2}-\d{2}T\d{2}-\d{2}\.md$", "", name)
        if name.endswith(".md"):
            name = name[:-3]
        slugs.append(name)
    return slugs


def index_cells(tid, path, text, columns):
    """Celulas DERIVADAS da task, na largura da tabela. `Resolve` so e montada para linha nova:
    as existentes carregam anotacao escrita a mao ('— (hipótese derrubada)') que nao esta em
    lugar nenhum do arquivo da task, e reconstrui-la apagaria informacao."""
    shas = commits_for_task(tid, reachable_only=True)
    feats = sorted(set(FEAT_ID_RE.findall(field(text, "Feature") or "")))
    cells = ["—"] * len(columns)
    cells[columns["Task"]] = "[%s](%s) — %s" % (tid, os.path.basename(path), task_title(tid, text))
    cells[columns["Status"]] = field(text, "Status") or "—"
    if "Feature" in columns:
        cells[columns["Feature"]] = ", ".join(feats) if feats else "—"
    if "Resolve" in columns:
        cells[columns["Resolve"]] = ", ".join(bug_slugs(path, text)) or "—"
    # `git log` vem do mais novo para o mais antigo; invertido fica na ordem em que a historia
    # aconteceu, que e como se le uma sequencia de commits.
    cells[columns["Commit"]] = " ".join("`" + s + "`" for s in reversed(shas)) if shas else "—"
    return cells


def render_row(cells):
    return "| " + " | ".join(c.strip() for c in cells) + " |"


def fill_index(tasks):
    """Mantem o indice de docs/task/README.md igual ao que os arquivos de task e o git dizem.

    ATUALIZA as celulas derivadas (`Status`, `Feature`, `Commit`) das linhas existentes e INSERE
    a linha das tasks que ainda nao tem nenhuma. Antes so sabia substituir, e quando a task nao
    tinha linha o `subn` devolvia 0 em silencio -- a saida dizia 'indice ja estava atualizado'
    com 8 das 9 tasks de fora. Ver `checktraceability-fix-nao-insere-task-ausente-do-indice`."""
    path = index_path()
    if not os.path.isfile(path):
        return
    text = read(path)
    newline = "\r\n" if "\r\n" in text else "\n"
    lines = text.split(newline)

    header, columns = index_columns(lines)
    if header is None:
        print("--fix: nao achei a tabela do indice em docs/task/README.md -- nada feito.")
        return

    rows = {}         # tid -> indice da linha
    last_row = header + 1
    for i in range(header + 1, len(lines)):
        m = TABLE_TASK_ROW_RE.match(lines[i])
        if m:
            rows[m.group(1)] = i
            last_row = i

    updated = 0
    for tid in sorted(rows):
        if tid not in tasks:
            fail(path, "o indice lista %s, que nao existe em docs/task/" % tid)
            continue
        i = rows[tid]
        cells = split_row(lines[i])
        want = index_cells(tid, tasks[tid][0], tasks[tid][1], columns)
        if len(cells) != len(want):
            fail(path, "a linha de %s tem %d celulas e o cabecalho tem %d"
                       % (tid, len(cells), len(want)))
            continue
        # So as celulas DERIVADAS; `Task` e `Resolve` sao prosa e ficam como estao.
        for name in ("Status", "Feature"):
            if name in columns:
                cells[columns[name]] = want[columns[name]]
        # `Commit` so e reescrita quando o git DESTE ramo tem o que escrever. As tasks 0001-0015
        # foram commitadas na linha anterior do produto, que nao tem historia comum com o fork:
        # `HEAD` nao as alcanca, e sobrescrever com `—` apagaria o unico registro daqueles hashes.
        if commits_for_task(tid, reachable_only=True):
            cells[columns["Commit"]] = want[columns["Commit"]]
        rendered = render_row(cells)
        if rendered != lines[i]:
            lines[i] = rendered
            updated += 1

    inserted = 0
    for tid in sorted(t for t in tasks if t not in rows):
        # Insere na ordem numerica: antes da primeira linha de uma task maior, senao no fim.
        after = [rows[t] for t in rows if t > tid]
        at = min(after) if after else last_row + 1
        lines.insert(at, render_row(index_cells(tid, tasks[tid][0], tasks[tid][1], columns)))
        rows = {t: (i + 1 if i >= at else i) for t, i in rows.items()}
        rows[tid] = at
        last_row = max(last_row + 1, at)
        inserted += 1

    if inserted or updated:
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(newline.join(lines))
        print("--fix: %d linha(s) inserida(s), %d atualizada(s) no indice." % (inserted, updated))
    else:
        print("--fix: nada a fazer, o indice ja descreve as tasks.")


def check_index(tasks):
    """Task concluida fora do indice REPROVA. Sem isto o `--fix` podia anunciar sucesso sem ter
    feito o trabalho e ninguem descobria -- o indice chegou a ter 11 linhas para 14 tasks."""
    path = index_path()
    if not os.path.isfile(path):
        return
    lines = read(path).splitlines()
    header, _columns = index_columns(lines)
    if header is None:
        fail(path, "nao ha tabela de indice com as colunas 'Task' e 'Commit'")
        return
    listed = {m.group(1) for m in (TABLE_TASK_ROW_RE.match(l) for l in lines[header + 1:]) if m}
    for tid, (_task_path, text) in sorted(tasks.items()):
        if tid in listed:
            continue
        if field(text, "Status") == "concluída" or commits_for_task(tid, reachable_only=True):
            fail(path, "%s esta concluída/commitada e nao tem linha no indice (rode --fix)" % tid)


if __name__ == "__main__":
    sys.exit(main())
