# TASK-0022: ajustar a primeira impressão do app — marca, tela de entrada e fundo

- **Status:** concluída
- **Criada em:** 2026-08-26
- **Concluída em:** 2026-08-26
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0022:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Feedback direto olhando o app rodando no Galaxy A12, na primeira vez que o fork foi instalado num
aparelho. Nada aqui saiu de leitura de código — cada item é algo que apareceu na tela.

## O que mudou

| Item | Antes | Agora |
|---|---|---|
| Abertura | vídeo com a marca ARMSX2 (`boot_intro.mp4`) | vai direto para o app |
| Tela de entrada | assistente de configuração de 3 páginas | biblioteca; configuração pela engrenagem |
| Marca no cabeçalho | logo-torre + wordmark `"ARMSX2"` fixo em código | nosso ícone + `R.string.app_name` |
| Tema padrão | `System` (branco num aparelho em modo claro) | escuro |
| Fundo da biblioteca | azul-real | azul-marinho escuro |
| Título do toolbar | `"Bi…"` / `"Tota…"` | `"Biblioteca"` / `"Total de jogos: 0"` |

## O assistente de configuração: o que eu tentei antes de acertar

A primeira tentativa foi **migrar** o "já configurei" do app anterior:

```
app antigo   arquivo "armsx2"   chave "onboarding_complete"
fork         arquivo "ARMSX2"   chave "setupComplete"
```

Nome de SharedPreferences é nome de **arquivo**, portanto case-sensitive: são dois arquivos
distintos e a leitura simplesmente devolve "nunca configurou". A migração funcionou — mas o
assistente continuou aparecendo, porque logo depois o fork faz:

```kotlin
if (setupComplete && !romsAccessible(this, romsDirs.value)) { setupComplete = false }
```

O app antigo não guardava pasta de ROMs no modelo deles (`romsDirs`), então a recuperação desfazia
a migração. E acrescentar `romsDirs` também não resolveria: em Android 13, `romsAccessible` só
aceita um caminho POSIX com **MANAGE_EXTERNAL_STORAGE** concedido, que o upgrade não traz.

Com a decisão de produto — *a única configuração é a da engrenagem* — isso ficou simples: o
assistente não roda no arranque, e a migração virou código morto e saiu junto.

**Antes de remover, verifiquei que a tela continua alcançável**, senão o usuário ficaria sem como
apontar as ROMs. Ela é a mesma tela do editor de configuração, e `reopenSetup()` é chamada de dois
lugares: a gaveta de navegação e o próprio aviso de biblioteca vazia (`HomeScreen`, ramo
`noFolders`) — o botão "Configurar" que aparece na tela.

Também foi desarmada a "recuperação de setup", que existia para repedir a permissão quando nenhuma
pasta estivesse alcançável. Ela agora só levanta a marca de diagnóstico; o caminho de volta à
configuração é o aviso na biblioteca, que o usuário escolhe em vez de sofrer.

## O fundo: corrigido no lugar errado da primeira vez

O primeiro ajuste mexeu só em `XmbGlView` (o caminho GLES3). O aparelho de teste é um Mali-G52 e cai
no **caminho 2D**, que carrega a própria constante — e cujo comentário diz, em texto, que ela deve
casar com a do GL. Casavam no azul; o ajuste as fez divergir, e a tela continuou azul.

São **três** lugares que decidem essa cor, e agora os três estão alinhados e avisam um do outro:

- `XmbGlView.BG_TOP` / `BG_BOT` — caminho GLES3
- `LibraryWaveBackground.DEFAULT_WAVE` — caminho 2D, para aparelhos onde o GLES3 não sobe
- `LibraryBackgroundColorPreferences.DefaultDisplayColor` — o que o seletor mostra como padrão

Azul-marinho escuro (`#16243D`) e não preto chapado: os dois caminhos desenham ondas sobre esse tom,
e sem alguma luminância elas somem.

## O título truncado: o diagnóstico errado que a evidência derrubou

A primeira leitura foi "a barra de status do sistema está sobrepondo o toolbar". **Errado**, e duas
observações derrubaram: o relógio continuou desenhado enquanto a tela apagava (a barra do sistema
teria apagado junto), e estava numa posição que não é a da barra do Samsung.

O relógio e a bateria eram do **próprio app** — `LibraryStatusCluster`, dentro do toolbar. Não havia
sobreposição: era disputa de largura. Num aparelho de 384dp, padding (36) + botão de menu (56) +
cluster (~90) + três botões redondos (~132) deixam ~70dp para o título, e "Biblioteca" em
`titleLarge` precisa de ~110dp.

O cluster cede em **retrato** e volta em paisagem — é o elemento mais largo que não é controle, e
paisagem é a orientação dos handhelds, para os quais ele foi pensado. Diminuir a fonte não
resolveria: mesmo em `titleMedium` seriam ~80dp contra os ~70 disponíveis.

## Como validar

Instalado e conferido por captura de tela no Galaxy A12 (SM-A127M, Android 13), a cada passo:
abertura sem vídeo, biblioteca como primeira tela, cabeçalho com o nosso nome e ícone, fundo escuro,
e o título do toolbar por extenso. **Feito.**

## Resultado

Entregue. As duas correções de rumo — o fundo no caminho errado e o diagnóstico errado do título —
só apareceram porque cada passo foi conferido numa captura em vez de no log do build. Fica como
método: **nesta árvore, olhar a tela; compilar não prova nada sobre o que o usuário vê.**
