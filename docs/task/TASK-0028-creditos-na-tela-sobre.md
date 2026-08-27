# TASK-0028: trazer os créditos da equipe para a tela Sobre

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0028:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Item "Sobre / créditos" do inventário de migração de 2026-08-27. O plano
([§5](../plano-fork-sobre-upstream.md)) o listava apontando `MainActivity` linhas ~3143 e ~3832 da
árvore anterior.

## O que estava lá, e o que já não precisava vir

O diálogo antigo tinha três partes:

| Parte | Situação no fork |
|---|---|
| `"RetroSystem PS2 (versão)"` | **já vem** — `ArmsLogo` usa `R.string.app_name`, e a versão está em "Informações da compilação" |
| `"by RetroSystem PS2 team"` | idem, é o nome do app |
| Contribuidores + agradecimentos | **faltava** — a tela deles lista compilação, hardware e repositórios, nunca as pessoas |

Só a terceira parte é trabalho. E ela **não é identidade nossa**: é a equipe do ARMSX2 e a cadeia de
quem o app usa (pontos2024, PCSX2, SDL, os autores dos ícones). Por isso o texto foi trazido palavra
por palavra, e não reescrito — a única edição foi tirar o número de versão do PCSX2, que estava
cravado em "v2.3.430" e desatualiza sozinho; a versão real do core já aparece acima, em "Versão do
emulador".

## Como foi feito

Um `GlassPanel` com `PanelTitle`, ao lado dos créditos de música e efeitos que a tela já tinha — o
mesmo padrão, no mesmo lugar. Texto em `about.credits.title` / `about.credits.body`, inglês em
`I18n.kt` e português em `assets/i18n/pt-BR.json`, porque nesta árvore texto de tela é chave de
i18n, não string literal.

## Como validar

Compilado e instalado no Galaxy A12. A tela Sobre foi alcançada e mostra o nosso nome, a versão
`1.0.24`, o core `197c72f23e` e os dados do aparelho.

O painel aparece, em português, com a lista completa:

```
Contribuidores principais:   • MoonPower — Desenvolvedor do app   ...
Agradecimentos:              • pontos2024 (base do emulador)   • PCSX2 (núcleo do emulador)
                             • SDL (SDL3)   • Fffathur   • vivimagic0
```

Conferido lendo a árvore de UI do rodapé da tela, logo acima dos créditos de música e efeitos que
já estavam lá. (A primeira tentativa foi interrompida — o aparelho caiu do `adb` no meio da
rolagem — e a task ficou registrada como pendente até esta verificação.)

## Resultado

Entregue.
