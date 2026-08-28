#!/usr/bin/env python3
"""Valida a rastreabilidade feature <-> task <-> bug descrita em docs/README.md.

Rode antes de todo push:

    python scripts/check_traceability.py

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
BUG_DIRS = [os.path.join(ROOT, "docs", "bugs", "open"),
            os.path.join(ROOT, "docs", "bugs", "done")]

TASK_RE = re.compile(r"^TASK-(\d{4})-.+\.md$")
FEAT_RE = re.compile(r"^FEAT-(\d{4})-.+\.md$")
TASK_ID_RE = re.compile(r"TASK-\d{4}")
FEAT_ID_RE = re.compile(r"FEAT-\d{4}")

TASK_STATUSES = {"aberta", "em andamento", "concluída", "revertida"}
FEAT_STATUSES = {"planejada", "em andamento", "concluída", "abandonada"}

problems = []


def fail(path, msg):
    problems.append("%s: %s" % (os.path.relpath(path, ROOT).replace("\\", "/"), msg))


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def field(text, name):
    """Le um campo do cabecalho no formato '- **Nome:** valor'."""
    m = re.search(r"^-\s+\*\*%s:\*\*\s*(.*)$" % re.escape(name), text, re.MULTILINE)
    return m.group(1).strip() if m else None


def is_empty(value):
    return value is None or value.strip() in ("", "—", "-", "nenhum", "nenhuma", "PENDENTE")


def git(*args):
    try:
        return subprocess.check_output(["git"] + list(args), cwd=ROOT,
                                       stderr=subprocess.DEVNULL).decode("utf-8", "replace").strip()
    except (subprocess.CalledProcessError, OSError):
        return None


def commit_is_reachable(sha):
    """Existir no banco de objetos nao basta: um commit orfao (pos --amend, pos rebase)
    ainda responde a cat-file e mentiria sobre o historico. Tem de ser ancestral de HEAD."""
    if git("cat-file", "-e", sha + "^{commit}") is None:
        return False
    try:
        subprocess.check_call(["git", "merge-base", "--is-ancestor", sha, "HEAD"],
                              cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except (subprocess.CalledProcessError, OSError):
        return False


def commits_for_task(tid):
    """Fonte de verdade do vinculo task->commit: o assunto do commit. Git nao mente.

    Procura em `--all`, nao em `HEAD`. O motivo e o fork: a branch do fork nasce da arvore do
    upstream e portanto NAO alcanca os commits da linha anterior do produto. Com `HEAD`, toda task
    concluida antes do fork passaria a reprovar aqui -- o registro mentiria sobre trabalho que
    existe e esta no repositorio, so que noutro ramo.

    Isto NAO enfraquece a checagem de commit orfao mais abaixo. Aquela existe para hash escrito a
    mao no campo `Commit:` de uma task, e um orfao de `--amend` nao e alcancavel por nenhuma ref --
    portanto `--all` continua sem o enxergar.
    """
    out = git("log", "--format=%h", "--grep=^" + tid + ":", "--extended-regexp", "--all")
    return [l for l in (out or "").splitlines() if l]


def md_files(directory, pattern):
    if not os.path.isdir(directory):
        return []
    return sorted(f for f in os.listdir(directory) if pattern.match(f))


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
        # o mesmo estrago que `commit_is_reachable`, mais abaixo, existe para detectar. O que
        # importa continua verificado: task concluida tem de ter ao menos um commit com o assunto.
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
        bugs_value = field(text, "Bugs que resolve") or ""
        for link in re.findall(r"\]\(([^)]+\.md)\)", bugs_value):
            bug_path = os.path.normpath(os.path.join(os.path.dirname(path), link))
            if not os.path.isfile(bug_path):
                fail(path, "bug referenciado nao existe: %s" % link)
            elif tid not in read(bug_path):
                fail(bug_path, "nao cita %s, que declara resolve-lo" % tid)

    # ---- features ----------------------------------------------------------
    for fid, (path, text) in sorted(feats.items()):
        status = field(text, "Status")
        if status not in FEAT_STATUSES:
            fail(path, "Status %r invalido (use: %s)" % (status, ", ".join(sorted(FEAT_STATUSES))))

        for tid in sorted(set(TASK_ID_RE.findall(text))):
            if tid not in tasks:
                fail(path, "lista %s, que nao existe em docs/task/" % tid)
            elif fid not in tasks[tid][1]:
                fail(tasks[tid][0], "nao aponta de volta para %s, que a lista" % fid)

    # ---- bugs resolvidos ---------------------------------------------------
    done_dir = BUG_DIRS[1]
    if os.path.isdir(done_dir):
        for name in sorted(os.listdir(done_dir)):
            if not name.endswith(".md") or name == "README.md":
                continue
            path = os.path.join(done_dir, name)
            text = read(path)
            # So exige task nos bugs fechados a partir da criacao do sistema.
            if "TASK-" not in text and "sem-task-legado" not in text:
                fail(path, "bug em done/ sem task que o tenha resolvido "
                           "(marque 'sem-task-legado' se for anterior ao sistema)")

    if problems:
        print("Rastreabilidade REPROVADA -- %d problema(s):\n" % len(problems))
        for p in problems:
            print("  - " + p)
        return 1

    print("OK -- %d task(s), %d feature(s), rastreabilidade consistente."
          % (len(tasks), len(feats)))

    if "--fix" in sys.argv:
        fill_index(tasks)
    return 0


def fill_index(tasks):
    """Escreve no indice de docs/task/README.md o hash real de cada task concluida,
    resolvido do git pelo assunto do commit. Roda DEPOIS do commit da task; o
    resultado vai num commit 'chore:' separado, porque so mexe em documentacao."""
    index = os.path.join(TASK_DIR, "README.md")
    if not os.path.isfile(index):
        return
    text = read(index)
    changed = 0
    for tid in sorted(tasks):
        found = commits_for_task(tid)
        if not found:
            continue
        # Todos os hashes, e nao so o primeiro: desde que "uma task = um commit" saiu (TASK-0042)
        # uma task pode ter varios, e antes disto `len(found) != 1` fazia a linha ficar SEM hash
        # nenhum, em silencio -- o pior dos resultados, porque parece indice preenchido.
        # `git log` ja vem do mais novo para o mais antigo; invertido fica na ordem em que a
        # historia aconteceu, que e como se le uma sequencia de commits.
        shas = " ".join("`" + s + "`" for s in reversed(found))
        # substitui a celula de commit da linha do indice que cita esta task
        pattern = re.compile(r"^(\|\s*\[" + tid + r"\].*\|\s*)([^|]*)(\|\s*)$", re.MULTILINE)

        def repl(m, shas=shas):
            return m.group(1) + " " + shas + " " + m.group(3)

        new_text, n = pattern.subn(repl, text)
        if n and new_text != text:
            text, changed = new_text, changed + n
    if changed:
        with open(index, "w", encoding="utf-8") as fh:
            fh.write(text)
        print("--fix: %d linha(s) do indice atualizada(s) com o hash real." % changed)
    else:
        print("--fix: indice ja estava atualizado.")


if __name__ == "__main__":
    sys.exit(main())
