# TASK-0032: mostrar o título sob a capa, para as variantes regionais deixarem de parecer repetidas

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0032:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Relato do usuário, com foto da tela: *"ao abrir o app e aparecer a grid com o catálogo aparecem
vários jogos repetidos. Coisa que não tínhamos na versão antiga."*

## Não eram repetidos

Conferido no manifesto antes de mexer em qualquer coisa:

```
$ cut -d'|' -f1 catalog_manifest_ps2.txt | sed 's|.*/||' | sort | uniq -d | wc -l
0
```

**Zero nomes de arquivo duplicados** em 12.628 entradas. O que a foto mostra são lançamentos
regionais distintos:

| Jogo | Entradas |
|---|---|
| 007 - Agent Under Fire | USA, Europe, Korea |
| 007 - Everything or Nothing | USA, Europe (En,Es,It,Nl,Sv), Europe (Fr,De), Japan, Korea |
| 007 - Nightfire | USA, Europe (De,Es), Europe (En,Fr,It,Nl,Sv), Japan, Korea |

O repositório de capas tem **uma arte por jogo, não por lançamento**. Cinco entradas, uma imagem.

## Por que só agora

A versão anterior mostrava o título sob toda capa do catálogo — `res/layout/item_catalog.xml`,
`tv_catalog_title`, `maxLines="2"`. Com o nome visível, ninguém confundia as cinco.

O fork nasce com `GridLabels.show = false`: só a arte. Isso é razoável para uma biblioteca de uma
dúzia de jogos que o usuário escolheu um a um; deixa de ser quando a grade carrega o catálogo
inteiro ([TASK-0025](TASK-0025-grade-unica-catalogo-na-biblioteca.md)).

## A correção

Duas linhas, as duas copiando o comportamento anterior:

1. `GridLabels.show` passa a **ligado por padrão**. Quem já tinha desligado a opção continua com ela
   desligada — só o padrão muda.
2. O rótulo da grade vai de `maxLines = 1` para **2**, o mesmo do cartão antigo. Isto **não** é
   cosmético: o que distingue "007 - Nightfire (USA)" de "007 - Nightfire (Korea)" está no fim do
   nome, e numa linha só o "…" come justamente a região — os cartões voltariam a parecer iguais.

## Como validar

No Galaxy A12, com o app aberto na biblioteca:

```
007 - Agent Under Fire            ← baixado (título vem do GameDB, sem a etiqueta de região)
007 - Agent Under Fire (Europe) (En,Fr,…
007 - Agent Under Fire (Korea)
007 - Everything or Nothing (Europe) (E…
007 - Everything or Nothing (Japan)
```

As três primeiras eram, na foto do relato, três cartões visualmente idênticos.

Segue truncando quando o nome traz a lista de idiomas ("(Europe) (En,Fr,…"), mas a **região** — a
parte que distingue — aparece nos dois casos.

## Resultado

Entregue. Não havia defeito de dados; havia informação faltando na tela.
