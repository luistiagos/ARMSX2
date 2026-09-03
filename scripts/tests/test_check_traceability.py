"""Testes de regressao do validador de rastreabilidade.

    python -m pytest scripts/tests -q

Cada teste monta um repositorio git de verdade num diretorio temporario e roda
`scripts/check_traceability.py` contra ele. Nao ha mock de git: os dois defeitos que motivaram
estes testes -- `--grep` casando o corpo do commit e `--fix` que nao insere -- so aparecem contra
o git real, e um mock teria concordado com o codigo errado.
"""

import os
import shutil
import subprocess
import sys

import pytest

SCRIPT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                      "check_traceability.py")

TASK = """# {tid}: {titulo}

- **Status:** {status}
- **Criada em:** 2026-09-03
- **Concluída em:** —
- **Feature:** {feature}
- **Bugs que resolve:** {bugs}
- **Commit:** —
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Task de teste.
"""

INDEX = """# Tasks

## Índice

| Task | Status | Feature | Resolve | Commit |
|---|---|---|---|---|
"""

BUG = """# Bug: {titulo}

- **Detectado em:** 2026-09-03
- **Origem:** `nenhuma`
- **Classe:** fail
- **Feature:** nenhuma
- **Tasks que o resolvem:** {tasks}

## Sintoma

Bug de teste.
"""


class Repo:
    def __init__(self, root):
        self.root = root
        os.makedirs(os.path.join(root, "docs", "task"))
        os.makedirs(os.path.join(root, "docs", "features"))
        os.makedirs(os.path.join(root, "docs", "bugs", "open", "armsx2-fork"))
        os.makedirs(os.path.join(root, "docs", "bugs", "done"))
        os.makedirs(os.path.join(root, "scripts"))
        shutil.copy(SCRIPT, os.path.join(root, "scripts", "check_traceability.py"))
        self.write("docs/task/README.md", INDEX)
        self.git("init", "-q", "-b", "main", ".")
        self.git("config", "user.email", "t@t")
        self.git("config", "user.name", "t")
        self.commit("chore: base")

    def write(self, rel, text):
        path = os.path.join(self.root, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)

    def read(self, rel):
        with open(os.path.join(self.root, rel.replace("/", os.sep)), encoding="utf-8") as fh:
            return fh.read()

    def task(self, tid, status="concluída", titulo="task de teste",
             feature="nenhuma", bugs="nenhum", slug="teste"):
        self.write("docs/task/%s-%s.md" % (tid, slug),
                   TASK.format(tid=tid, titulo=titulo, status=status, feature=feature, bugs=bugs))

    def bug(self, name, tasks="nenhuma", titulo="bug de teste", folder="open/armsx2-fork"):
        self.write("docs/bugs/%s/%s.md" % (folder, name), BUG.format(titulo=titulo, tasks=tasks))

    def index_row(self, tid, slug="teste", titulo="task de teste",
                  status="concluída", commit="—"):
        path = os.path.join(self.root, "docs", "task", "README.md")
        with open(path, "a", encoding="utf-8") as fh:
            fh.write("| [%s](%s-%s.md) — %s | %s | — | — | %s |\n"
                     % (tid, tid, slug, titulo, status, commit))

    def git(self, *args):
        return subprocess.run(["git"] + list(args), cwd=self.root, check=True,
                              capture_output=True, text=True).stdout

    def commit(self, subject, body=None, allow_empty=True):
        self.git("add", "-A")
        args = ["commit", "-q", "-m", subject]
        if body:
            args += ["-m", body]
        if allow_empty:
            args.append("--allow-empty")
        self.git(*args)
        return self.git("rev-parse", "--short", "HEAD").strip()

    def check(self, *extra):
        r = subprocess.run([sys.executable, os.path.join("scripts", "check_traceability.py")]
                           + list(extra), cwd=self.root, capture_output=True, text=True)
        return r.returncode, r.stdout + r.stderr


@pytest.fixture
def repo(tmp_path):
    return Repo(str(tmp_path / "r"))


# ---- o vinculo task->commit e o ASSUNTO, nao a mensagem inteira -------------

def test_mencao_no_corpo_nao_conta_como_commit_da_task(repo):
    """`git log --grep='^TASK-0099:'` casava esta mensagem porque `^` casa o inicio de qualquer
    linha do corpo. A task fica 'concluída' sem nunca ter sido commitada, e o validador aprovava."""
    repo.task("TASK-0099")
    repo.index_row("TASK-0099")
    repo.commit("chore: mexe noutra coisa",
                body="Contexto:\nTASK-0099: isto e so uma mencao no CORPO, nao o assunto")

    code, out = repo.check()
    assert code == 1
    assert "nenhum commit alcancavel tem assunto 'TASK-0099: ...'" in out


def test_assunto_de_verdade_conta(repo):
    repo.task("TASK-0099")
    repo.index_row("TASK-0099")
    repo.commit("TASK-0099: faz o trabalho de verdade")

    code, out = repo.check()
    assert code == 0, out


def test_prefixo_maior_nao_casa(repo):
    """`TASK-0099` nao pode ser satisfeita por um commit de `TASK-00991` -- o `:` e o separador."""
    repo.task("TASK-0099")
    repo.index_row("TASK-0099")
    repo.commit("TASK-00991: outra task")

    code, out = repo.check()
    assert code == 1
    assert "TASK-0099: ..." in out


# ---- --fix INSERE a linha que falta ----------------------------------------

def test_fix_insere_linha_ausente(repo):
    """O defeito original: `subn` devolvia 0, o laco seguia, e a saida dizia
    'indice ja estava atualizado' com a task inteira de fora."""
    repo.task("TASK-0099")
    sha = repo.commit("TASK-0099: faz o trabalho")

    code, out = repo.check("--fix")
    assert code == 0, out
    assert "1 linha(s) inserida(s)" in out

    index = repo.read("docs/task/README.md")
    assert "[TASK-0099](TASK-0099-teste.md)" in index
    assert "`%s`" % sha in index


def test_task_concluida_fora_do_indice_reprova(repo):
    """Sem isto o `--fix` podia anunciar sucesso sem ter feito o trabalho e ninguem descobria."""
    repo.task("TASK-0099")
    repo.commit("TASK-0099: faz o trabalho")

    code, out = repo.check()
    assert code == 1
    assert "TASK-0099" in out and "nao tem linha no indice" in out


def test_fix_nao_deixa_espaco_duplo_e_e_idempotente(repo):
    repo.task("TASK-0099")
    repo.commit("TASK-0099: faz o trabalho")
    repo.check("--fix")
    primeiro = repo.read("docs/task/README.md")

    code, out = repo.check("--fix")
    assert code == 0, out
    assert "nada a fazer" in out
    assert repo.read("docs/task/README.md") == primeiro
    assert "|  `" not in primeiro


def test_fix_nao_assume_que_commit_e_a_ultima_coluna(repo):
    """O regex antigo ancorava em `$` e por isso escrevia o hash na coluna errada sempre que
    `Commit` nao fosse a ultima. Ja produziu linhas com o hash gravado em `Feature`."""
    repo.task("TASK-0099")
    repo.write("docs/task/README.md",
               "# Tasks\n\n| Task | Status | Commit | Nota |\n|---|---|---|---|\n")
    sha = repo.commit("TASK-0099: faz o trabalho")

    code, out = repo.check("--fix")
    assert code == 0, out
    linha = [l for l in repo.read("docs/task/README.md").splitlines()
             if l.startswith("| [TASK-0099]")][0]
    celulas = [c.strip() for c in linha.strip("|").split("|")]
    assert celulas[2] == "`%s`" % sha
    assert celulas[3] == "—"


def test_fix_preserva_hash_que_head_nao_alcanca(repo):
    """As TASK-0001..0015 foram commitadas na linha anterior do produto, que nao tem historia
    comum com o fork. Sobrescrever com `—` apagaria o unico registro daqueles hashes."""
    repo.task("TASK-0099", status="concluída")
    repo.commit("TASK-0099: entra pelo assunto")
    repo.git("branch", "outro")
    repo.git("checkout", "-q", "--orphan", "fork")
    repo.git("rm", "-rq", "--cached", ".")
    repo.task("TASK-0098")
    repo.index_row("TASK-0098")
    repo.index_row("TASK-0099", commit="`c0ffee1`")
    repo.commit("TASK-0098: task do fork", allow_empty=False)

    repo.check("--fix")
    assert "`c0ffee1`" in repo.read("docs/task/README.md")


# ---- status cruzado feature <-> task ---------------------------------------

def test_status_divergente_entre_feature_e_task_reprova(repo):
    repo.task("TASK-0099", status="aberta",
              feature="[FEAT-0099](../features/FEAT-0099-teste.md)")
    repo.index_row("TASK-0099", status="aberta")
    repo.write("docs/features/FEAT-0099-teste.md",
               "# FEAT-0099: feature de teste\n\n- **Status:** planejada\n\n"
               "## Tasks\n\n| Task | Status | Descrição |\n|---|---|---|\n"
               "| [TASK-0099](../task/TASK-0099-teste.md) | concluída | x |\n")
    repo.commit("chore: docs")

    code, out = repo.check()
    assert code == 1
    assert "lista TASK-0099 como 'concluída', mas a task declara 'aberta'" in out


def test_task_citada_so_na_prosa_da_feature_nao_e_exigida(repo):
    """`TASK_ID_RE.findall(text)` varria o texto inteiro: uma frase sobre trabalho futuro fazia o
    validador exigir um arquivo de task que ninguem escreveu ainda."""
    repo.write("docs/features/FEAT-0099-teste.md",
               "# FEAT-0099: feature de teste\n\n- **Status:** planejada\n\n"
               "Adiante, a TASK-0500 deve cuidar disto.\n\n"
               "## Tasks\n\n| Task | Status | Descrição |\n|---|---|---|\n")
    repo.task("TASK-0099", status="aberta")
    repo.index_row("TASK-0099", status="aberta")
    repo.commit("chore: docs")

    code, out = repo.check()
    assert code == 0, out


# ---- bug -> task, o sentido que faltava ------------------------------------

def test_link_de_mao_unica_reprova(repo):
    repo.task("TASK-0099", status="aberta")
    repo.index_row("TASK-0099", status="aberta")
    repo.bug("meu-bug_2026-09-03T10-00",
             tasks="[TASK-0099](../../../task/TASK-0099-teste.md)")
    repo.commit("chore: docs")

    code, out = repo.check()
    assert code == 1
    assert "nao lista em **Bugs que resolve**" in out


def test_link_de_mao_dupla_passa(repo):
    repo.task("TASK-0099", status="aberta",
              bugs="[meu-bug](../bugs/open/armsx2-fork/meu-bug_2026-09-03T10-00.md)")
    repo.index_row("TASK-0099", status="aberta")
    repo.bug("meu-bug_2026-09-03T10-00",
             tasks="[TASK-0099](../../../task/TASK-0099-teste.md)")
    repo.commit("chore: docs")

    code, out = repo.check()
    assert code == 0, out


def test_bug_que_nega_o_vinculo_nao_exige_backlink(repo):
    """`**nenhuma** — a TASK-0099 registra o defeito e **não o corrige**` cita a task de proposito
    para dizer que ela NAO resolve o bug. Exigir backlink dai apagaria a explicacao."""
    repo.task("TASK-0099", status="aberta")
    repo.index_row("TASK-0099", status="aberta")
    repo.bug("meu-bug_2026-09-03T10-00",
             tasks="**nenhuma** — a [TASK-0099](../../../task/TASK-0099-teste.md) só instrumenta")
    repo.commit("chore: docs")

    code, out = repo.check()
    assert code == 0, out


def test_campo_multilinha_e_lido_inteiro(repo):
    """A TASK-0045 lista os dois bugs que resolve em linhas indentadas. Ler so a primeira
    escondia o segundo link e fazia aquele bug parecer orfao de task."""
    repo.task("TASK-0099", status="aberta",
              bugs="[a](../bugs/open/armsx2-fork/bug-a_2026-09-03T10-00.md),\n"
                   "  [b](../bugs/open/armsx2-fork/bug-b_2026-09-03T10-00.md)")
    repo.index_row("TASK-0099", status="aberta")
    repo.bug("bug-a_2026-09-03T10-00", tasks="[TASK-0099](../../../task/TASK-0099-teste.md)")
    repo.bug("bug-b_2026-09-03T10-00", tasks="[TASK-0099](../../../task/TASK-0099-teste.md)")
    repo.commit("chore: docs")

    code, out = repo.check()
    assert code == 0, out


def test_bug_fechado_precisa_declarar_a_task_no_campo(repo):
    """O check antigo aceitava a substring 'TASK-' em qualquer lugar do texto -- uma frase como
    'hipotese eliminada pela TASK-0008' bastava."""
    repo.bug("fechado_2026-09-03T10-00", tasks="nenhuma", folder="done")
    repo.write("docs/bugs/done/fechado_2026-09-03T10-00.md",
               repo.read("docs/bugs/done/fechado_2026-09-03T10-00.md")
               + "\nHipotese eliminada pela TASK-0099.\n")
    repo.task("TASK-0099", status="aberta")
    repo.index_row("TASK-0099", status="aberta")
    repo.commit("chore: docs")

    code, out = repo.check()
    assert code == 1
    assert "bug em done/ sem task declarada" in out


def test_bug_fechado_marcado_como_legado_passa(repo):
    repo.bug("fechado_2026-09-03T10-00", tasks="sem-task-legado", folder="done")
    repo.commit("chore: docs")

    code, out = repo.check()
    assert code == 0, out


# ---- Publicado em ----------------------------------------------------------

def test_travessao_com_comentario_nao_conta_como_valor(repo):
    """`— (o publicador existe, mas nada foi publicado)` e campo VAZIO. Lido como valor, ele
    virava fronteira de publicacao e reprovava tasks que nunca foram ao ar."""
    repo.task("TASK-0098")
    repo.index_row("TASK-0098", slug="teste")
    repo.commit("TASK-0098: primeira")
    repo.task("TASK-0099")
    repo.write("docs/task/TASK-0099-teste2.md",
               repo.read("docs/task/TASK-0099-teste.md")
               .replace("- **Publicado em:** —",
                        "- **Publicado em:** — (o publicador existe, mas nada foi publicado)"))
    os.remove(os.path.join(repo.root, "docs", "task", "TASK-0099-teste.md"))
    repo.index_row("TASK-0099", slug="teste2")
    repo.commit("TASK-0099: segunda")

    code, out = repo.check()
    assert code == 0, out


def test_task_ja_publicada_sem_o_campo_reprova(repo):
    repo.task("TASK-0098")
    repo.index_row("TASK-0098")
    repo.commit("TASK-0098: entrou antes e foi ao ar")
    repo.write("docs/task/TASK-0099-publica.md",
               TASK.format(tid="TASK-0099", titulo="publicar", status="concluída",
                           feature="nenhuma", bugs="nenhum")
               .replace("- **Publicado em:** —", "- **Publicado em:** 1.0.24 / versionCode 38"))
    repo.index_row("TASK-0099", slug="publica")
    repo.commit("TASK-0099: publica a 1.0.24")

    code, out = repo.check()
    assert code == 1
    assert "**Publicado em** esta vazio" in out
