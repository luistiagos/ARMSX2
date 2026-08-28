# TASK-0043: tirar da tela os avisos sobrepostos ao boot de um jogo

- **Status:** concluída
- **Criada em:** 2026-08-28
- **Concluída em:** 2026-08-28
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0043:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Observado por acaso durante a validação da [TASK-0041](TASK-0041-permissao-de-notificacao-do-download.md):
um toque errado lançou um jogo e o emulador mostrou, sobreposto à tela, um aviso do upstream. Relatado
ao usuário, que pediu a task.

Nasceu **sem implementação decidida** — é decisão de produto e de licença, não de código — e por isso
as seções abaixo preservam as opções que foram postas na mesa antes da escolha. A decisão veio
depois, e está registrada em **Decisão**.

## O que é, exatamente

[`platforms/android/app/src/main/cpp/native-lib.cpp:2561`](../../platforms/android/app/src/main/cpp/native-lib.cpp),
em `Host::OnGameChanged` — ou seja, **a cada jogo que inicia**:

```cpp
// Free-software / anti-resale notice on each game boot, rendered through PCSX2's own OSD (the
// same message system + renderer as the FPS/stats overlay) so it reads as a native emulator
// pop-up rather than an Android layer drawn on top.
if (current_crc != 0 || !disc_path.empty() || !title.empty()) {
    Host::AddKeyedOSDMessage("armsx2_free_software_notice",
        "You are using ARMSX2, and it should not be sold, or distributed as part of any other "
        "app. If you paid for this app, you should get your money back.",
        10.0f);
}
```

Dez segundos, no OSD do próprio PCSX2, em inglês. Confirmado na tela do SM-A127M no APK
`github/release`.

## Por que é um problema para nós, e não só um detalhe

Três coisas distintas, que convém não misturar:

1. **Diz o nome errado.** "ARMSX2" é o produto do upstream. Todo o resto do app já fala
   RetroSystem PS2 — foi o assunto da [TASK-0017](TASK-0017-identidade-do-produto.md), e a
   [TASK-0041](TASK-0041-permissao-de-notificacao-do-download.md) acabou de corrigir a mesma
   inconsistência na notificação de download. Aqui, um usuário nosso lê o nome de outro produto.
2. **Contradiz a nossa distribuição.** O texto diz que o app *não deve ser distribuído como parte de
   nenhum outro app*. O RetroSystem PS2 é exatamente isto: uma distribuição do ARMSX2 com a nossa
   identidade, publicada em `versions.digitalstoregames.com`.
3. **Manda o usuário pedir dinheiro de volta.** *"If you paid for this app, you should get your money
   back."* Se houver qualquer cobrança envolvida no nosso canal, o app está instruindo o cliente a
   pedir reembolso — a cada jogo que ele abre.

## O que pesa do outro lado

O repositório é **GPLv3** (`COPYING.GPLv3`), e este aviso é de um autor anterior. A GPLv3, na seção
7(b), permite que um licenciante exija a **preservação de avisos legais razoáveis ou de atribuição
de autoria** — e remover um aviso coberto por essa cláusula é violação de licença, não escolha
editorial.

Não sei dizer, e este documento não deve fingir que sabe, se esta mensagem é um aviso do 7(b) ou uma
mensagem que o autor pôs por vontade própria e que pode ser alterada como qualquer outro código GPL.
**A diferença decide a resposta**, e é pergunta para quem responde pela licença, não para quem
escreve o código.

## Caminhos possíveis

| # | Caminho | Observação |
|---|---|---|
| 1 | **Deixar como está.** | Nada a fazer. O usuário lê "ARMSX2" e a instrução de reembolso. |
| 2 | **Trocar só o nome do produto**, mantendo a substância (software livre, não deve ser vendido). | Mexe menos no aviso do autor; resolve o item 1 e parte do 2. Ainda diz para pedir reembolso. |
| 3 | **Reescrever como aviso de software livre**, sem a frase do reembolso. | Preserva atribuição e o espírito; altera o texto do autor. Precisa da leitura de licença acima. |
| 4 | **Remover.** | O mais arriscado sob a GPLv3 7(b), e o que mais parece apagar crédito de terceiro. Não recomendo sem parecer explícito. |

## Decisão

**Caminho 4 — remover.** Decidido pelo usuário em 2026-08-28, com a ressalva da GPLv3 7(b) acima
posta na mesa e relida na conversa. Fica registrado aqui que a escolha foi consciente, e não
descuido: a responsabilidade pela leitura de licença é de quem responde pelo produto.

## A segunda mensagem

O mesmo pedido incluiu *"a outra que aparece por cima quando inicia algum jogo"*. Capturada no
aparelho, é do **núcleo do PCSX2**, não do aviso acima:

> 🖌 Current Blending Accuracy is Basic.
> Recommended Blending Accuracy for this game is Full.
> You can adjust the blending level in Game Properties to improve graphical quality, but this will
> increase system requirements.

`pcsx2/GameDatabase.cpp:1110`, chave `HWBlendingWarning`. E ela não está sozinha: o mesmo arquivo
posta **cinco** avisos no boot, e eles se dividem em duas naturezas que não devem receber o mesmo
tratamento.

| Chave | Linha | Dispara quando | Natureza |
|---|---|---|---|
| `HWBlendingWarning` | 1110 | o padrão do app é menor que o recomendado no GameDB | nag de configuração |
| `HWAlphaTestWarning` | 1136 | idem | nag de configuração |
| `HWAA1Warning` | 1160 | idem | nag de configuração |
| `CoreFixesWarning` | 701 | o usuário **desligou** correções automáticas do core | diagnóstico |
| `HWFixesWarning` | 1208 | o usuário **desligou** correções automáticas de GS | diagnóstico |

Os três primeiros aparecem numa **instalação limpa, sem ação nenhuma do usuário** — é o caso que foi
fotografado: "Current is Basic, recommended is Full", com o app recém-instalado. São eles o
incômodo.

Os dois últimos só aparecem para quem foi lá e mudou alguma coisa, e explicam por que um jogo pode
estar se comportando mal. **Ficam.** Apagá-los transformaria uma escolha do usuário num defeito sem
explicação.

## Escopo

**Entra:**

1. Remover o aviso anti-revenda de `Host::OnGameChanged`
   ([native-lib.cpp:2561](../../platforms/android/app/src/main/cpp/native-lib.cpp)).
2. Remover os três nags de configuração (`HWBlendingWarning`, `HWAlphaTestWarning`, `HWAA1Warning`)
   em `pcsx2/GameDatabase.cpp`.

**Fica de fora, deliberadamente:**

- `CoreFixesWarning` e `HWFixesWarning`, pelo motivo acima.
- Qualquer outra mensagem de OSD (FPS, savestate, conquistas): não foram pedidas e não são nag de
  boot.
- Mexer no mecanismo de OSD em si.

## Custo de manutenção, dito antes

`pcsx2/GameDatabase.cpp` é código do núcleo, não da camada Android: cada sync com o upstream vai
conflitar nesses três pontos. É o preço de alterar o core em vez da cola, e está escrito aqui para
quem for fazer o próximo sync não achar que é acidente. Os três trechos ficam com comentário no
lugar, para o conflito ser resolvido com contexto.

## Como validar

1. `assembleGithubRelease`, instalar no SM-A127M.
2. Abrir um jogo da biblioteca e observar os primeiros 15 segundos: **nenhuma** faixa sobreposta.
3. Conferir que o jogo em si continua bootando e rodando.

## Resultado

Entregue. Validado no SM-A127M com o APK `github/release`, abrindo "007 - Agent Under Fire" e
capturando aos 4 s, 7 s, 10 s e 14 s: **nenhuma faixa sobreposta** em nenhum dos quatro pontos. Aos
7 s, exatamente onde as duas mensagens apareciam lado a lado na captura anterior, a tela está limpa;
o jogo boota normalmente (EA Games → MGM Interactive).

O que mudou:

| Arquivo | Mudança |
|---|---|
| `native-lib.cpp`, `Host::OnGameChanged` | Corpo removido; a função fica, porque é callback obrigatório de `Host` e também dispara no shutdown/eject |
| `pcsx2/GameDatabase.cpp` ×3 | `AddKeyedOSDMessage` sai; o `RemoveKeyedOSDMessage` fica, para limpar a mensagem se ela já estiver na tela por outro caminho |

Os três `case`s do GameDatabase mantiveram a estrutura (`if (quiet) break;` e o `break;` do `case`),
então o `switch` continua igual e nenhuma variável ficou órfã — o build não emitiu nenhum
`unused variable` novo.

Os quatro comentários deixados no lugar dizem que a ausência é deliberada, e não merge malfeito.
É a dívida real desta task: `pcsx2/GameDatabase.cpp` é núcleo, não cola Android, e cada sync com o
upstream vai conflitar nesses três pontos.
