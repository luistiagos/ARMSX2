# TASK-0043: decidir o que fazer com o aviso anti-revenda do upstream, que aparece a cada boot

- **Status:** aberta
- **Criada em:** 2026-08-28
- **Concluída em:** —
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0043:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Observado por acaso durante a validação da [TASK-0041](TASK-0041-permissao-de-notificacao-do-download.md):
um toque errado lançou um jogo e o emulador mostrou, sobreposto à tela, um aviso do upstream. Relatado
ao usuário, que pediu a task.

> **Esta task não tem implementação decidida.** É uma decisão de produto e de licença, não de código,
> e por isso está `aberta` com opções em vez de um escopo fechado.

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
| 3 | **Reescrever como aviso de software livre**, sem a frase do reembolso — algo como "este é um software livre, baseado em PCSX2 e ARMSX2; se pagou por ele, saiba que ele é gratuito". | Preserva atribuição e o espírito; altera o texto do autor. Precisa da leitura de licença acima. |
| 4 | **Remover.** | O mais arriscado sob a GPLv3 7(b), e o que mais parece apagar crédito de terceiro. Não recomendo sem parecer explícito. |

Sem recomendação técnica aqui de propósito: nenhuma das quatro é melhor *em código*, e a escolha é
de produto e de licença.

## Como validar (qualquer que seja o caminho ≥ 2)

1. Compilar `assembleGithubRelease` e instalar no SM-A127M.
2. Abrir um jogo qualquer da biblioteca.
3. Observar o OSD nos primeiros 10 segundos: conferir o texto exibido contra o texto decidido.
4. Abrir um segundo jogo sem fechar o app — a mensagem é *keyed*, então deve reaparecer atualizada,
   não duplicada.

## Resultado

Preenchido ao concluir.
