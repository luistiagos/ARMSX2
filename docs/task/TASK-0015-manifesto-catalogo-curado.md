# TASK-0015: adotar o manifesto de catálogo curado e impedir que a ferramenta o desfaça

- **Status:** concluída
- **Criada em:** 2026-08-26
- **Concluída em:** 2026-08-26
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0015:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Registrar em git a ordenação do `catalog_manifest_ps2.txt` que está em uso e é a correta, e corrigir
o `sort_manifest.py`, que hoje a desfaria em silêncio.

## O que foi encontrado

O manifesto estava modificado na working tree, com uma cópia idêntica não rastreada na raiz. A
comparação byte a byte mostrou que os dois arquivos são o mesmo conteúdo, e que ele **não** é a saída
do `sort_manifest.py` atual: rodar o script reordenaria as 12.628 linhas inteiras.

A ordem em uso tem dois blocos, cada um alfabético:

| Bloco | Linhas | Conteúdo |
|---|---|---|
| 1 | 1–1780 | entradas **com capa**: 1.779 `.chd` com URL **mais um `.iso` promovido à mão** |
| 2 | 1781–12628 | todo o resto — `.chd` sem capa, `.iso`, `.7z` —, com e sem URL misturados |

O `.iso` promovido é `ps2/PS2-Super Bomba Patch 2026 - Andre Henning.iso`, e ele é a peça que decide
o desenho da correção: **prova que o bloco 1 não é derivável de uma regra pura sobre o conteúdo da
linha.** Alguém decidiu que aquela entrada aparece entre as com capa apesar de não ser `.chd`. Uma
regra automática apagaria essa decisão sem avisar.

Havia ainda um segundo defeito, menor e mais ruidoso: o script abria o arquivo em modo texto, então
no Windows escrevia **CRLF** enquanto o arquivo versionado está em **LF**. Toda execução produzia um
diff de 12.628 linhas sem mudar uma vírgula do conteúdo.

## Escopo

**Entra:**
- `app/src/main/assets/catalog_manifest_ps2.txt` — a ordenação curada, commitada.
- `catalog_manifest_ps2.txt` na raiz — passa a ser **rastreado**. É a fonte que o script lê; estava
  fora do git, então a curadoria só existia na máquina de quem a fez.
- `sort_manifest.py` — passa a **aprender** a curadoria do próprio arquivo em vez de re-derivá-la:
  as entradas com capa que já estão no bloco 1 sem serem `.chd` continuam lá. E escreve com
  `newline='\n'`.

**NÃO entra:**
- Mudar o conteúdo do catálogo. Nenhuma entrada foi acrescentada, removida ou reescrita — só a
  ordem, e ela já estava como ficou.
- Deduplicar os dois arquivos. Eles são byte a byte iguais e é um desperdício de ~926 KB no
  repositório, mas resolver isso exige decidir se o asset passa a ser gerado no build — decisão de
  outra task.

## Como validar

```bash
python sort_manifest.py
git diff --stat            # tem de ser vazio
```

**Executado.** O script relata `12628 entradas, 1780 no bloco de capas (1 promocoes manuais)` e
`ordem ja estava correta`, e o arquivo resultante é byte a byte igual ao que está em uso.

## Resultado

Entregue. O ganho que não estava no pedido: antes desta task, a curadoria existia só na working tree
de uma máquina, e a ferramenta versionada ao lado dela a destruiria na próxima execução. Agora ela
está em git e a ferramenta a preserva.
