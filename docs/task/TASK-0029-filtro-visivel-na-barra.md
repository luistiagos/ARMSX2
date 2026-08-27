# TASK-0029: fazer o filtro "Só os baixados" se anunciar na barra

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0029:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

O usuário reportou que a biblioteca mostrava só o jogo baixado em vez do catálogo completo. **Não
era defeito da grade:** o filtro "Só os baixados" da [TASK-0025](TASK-0025-grade-unica-catalogo-na-biblioteca.md)
estava ligado, deixado assim por um teste meu na sessão anterior.

Isso aconteceu **duas vezes** — a segunda comigo, ao navegar o menu por D-pad. Um estado que engana
duas vezes em duas sessões não é acidente de uso: é falta de sinal.

## O problema real

O filtro é global, persiste entre sessões e esconde 12.627 dos 12.628 cartões. Ligado, a biblioteca
fica indistinguível de uma que perdeu o catálogo — e nada na tela principal dizia que havia um
filtro. O desligamento existe, no menu de três pontos, mas só encontra quem já desconfia.

## A correção

A barra passa a dizer. Com o filtro ativo:

```
Só os baixados · Total de jogos: 1
```

O rótulo é a mesma chave do item de menu (`games.overflow.onlyDownloaded`), então a palavra que
liga é a mesma que aparece ligada — quem lê a barra sabe exatamente o que procurar no menu.

Deliberadamente **não** foi feito: desligar o filtro sozinho no arranque. Ele persiste porque é
escolha do usuário; o que faltava era ele ser visível, não ser esquecido.

## Como validar

No Galaxy A12, ligando e desligando pelo menu de três pontos:

| Estado | Barra |
|---|---|
| ligado | `Só os baixados · Total de jogos: 1` |
| desligado | `Total de jogos: 12628` |

## Resultado

Entregue.
