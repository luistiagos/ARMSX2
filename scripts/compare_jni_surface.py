#!/usr/bin/env python3
"""Compara a superficie JNI Java_kr_co_iefriends_pcsx2_* entre duas arvores.

    python scripts/compare_jni_surface.py app/src/main/cpp/main.cpp <upstream>/native-lib.cpp

Existe porque o transplante sobre o upstream depende deste numero e o numero MUDA: medido contra
be72a8e1eb (18/08/2026) dava 31 identicos e 2 divergentes; contra 662b114168 (26/08/2026) da 30 e 3.
A divergencia nova era `getGameCRC`, que retorna jint do nosso lado e jstring do deles.

Por que a assinatura importa, e nao so o nome: **JNI liga por NOME**. Um metodo com o mesmo nome e
tipos diferentes compila, linka e roda -- e entrega lixo. No caso do getGameCRC, o `int` declarado no
Java receberia os 32 bits baixos de um ponteiro jstring: nao crasha, produz um CRC errado em
silencio, e o CRC alimenta busca de capas, overrides de GameDB e a chave do GraphicsHealthMonitor.
Este script existe para que essa classe de divergencia seja encontrada por comando, e nao por
sintoma em producao.

Saida: contagens, a lista das divergentes com as duas assinaturas lado a lado, e a lista dos metodos
que so existem de um dos lados. Codigo de saida 0 sempre -- e uma medicao, nao um portao.
"""

import io
import re
import sys

DECL = re.compile(
    r'JNIEXPORT\s+(?P<ret>[A-Za-z_][A-Za-z0-9_]*)\s+JNICALL\s*\n?\s*'
    r'(?P<name>Java_kr_co_iefriends_pcsx2_[A-Za-z0-9_]+)\s*\((?P<args>[^)]*)\)',
    re.MULTILINE)

PREFIX = 'Java_kr_co_iefriends_pcsx2_'


def normalize_args(args):
    """Reduz a lista de parametros aos TIPOS, descartando os nomes."""
    out = []
    for raw in args.split(','):
        arg = raw.strip()
        if not arg:
            continue
        arg = re.sub(r'\s*\bconst\b\s*', '', arg)
        arg = arg.replace('*', ' * ')
        tokens = arg.split()
        # O ultimo token e o nome do parametro quando ha mais de um token e ele nao e
        # um tipo por si so (`void`, `jclass`, `jobject`, `JNIEnv`).
        if (len(tokens) > 1
                and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', tokens[-1])
                and tokens[-1] not in ('void', 'jclass', 'jobject', 'JNIEnv')):
            tokens = tokens[:-1]
        out.append(' '.join(tokens))
    return out


def parse(path):
    source = io.open(path, encoding='utf-8', errors='replace').read()
    found = {}
    for match in DECL.finditer(source):
        args = normalize_args(match.group('args'))
        # Os dois primeiros parametros sao sempre JNIEnv* e jclass/jobject.
        found[match.group('name')] = (match.group('ret'), tuple(args[2:]))
    return found


def render(signature):
    return '%s(%s)' % (signature[0], ', '.join(signature[1]))


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2

    ours = parse(argv[1])
    theirs = parse(argv[2])

    common = sorted(set(ours) & set(theirs))
    only_ours = sorted(set(ours) - set(theirs))
    only_theirs = sorted(set(theirs) - set(ours))
    identical = [n for n in common if ours[n] == theirs[n]]
    divergent = [n for n in common if ours[n] != theirs[n]]

    print('%s: %d metodos' % (argv[1], len(ours)))
    print('%s: %d metodos' % (argv[2], len(theirs)))
    print()
    print('em comum ............... %d' % len(common))
    print('  assinatura identica .. %d' % len(identical))
    print('  assinatura divergente. %d   <-- reconciliar ANTES de rodar Java' % len(divergent))
    print('so nossos (reimplementar) %d' % len(only_ours))
    print('so deles (de graca) ..... %d' % len(only_theirs))

    if divergent:
        print()
        print('=== DIVERGENTES ===')
        for name in divergent:
            print('  %s' % name[len(PREFIX):])
            print('      nosso : %s' % render(ours[name]))
            print('      deles : %s' % render(theirs[name]))

    if only_ours:
        print()
        print('=== SO NOSSOS ===')
        for name in only_ours:
            print('  %s' % name[len(PREFIX):])

    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
