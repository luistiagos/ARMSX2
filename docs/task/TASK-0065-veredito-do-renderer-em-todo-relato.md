# TASK-0065: o veredito do renderer automático em todo relato, e a regra `auto-vulkan` registrada

- **Status:** em andamento
- **Criada em:** 2026-08-31
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [veredito-do-renderer-automatico-so-chega-a-relato-quando-ha-crash](../bugs/open/veredito-do-renderer-automatico-so-chega-a-relato-quando-ha-crash_2026-08-31T19-10.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0065:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## De onde vem

Item 1 do pacote aberto pela [TASK-0064](TASK-0064-devolver-o-controle-do-piso-de-z.md): a regra
`gl-arm-g52-r38-auto-vulkan` foi publicada no commit `bf45520833` (assunto `*`) **sem task e sem
registro de bug**. Ela troca o renderizador de todo Mali-G52 em r38, e ninguém consegue revisar,
justificar ou aposentar uma decisão dessas a partir de um comentário de código.

## Duas coisas, e a segunda é a que vale

### 1. Registrar (documentação, sem código)

Dois registros escritos nesta task:

- [gl-mali-g52-r38-tela-preta-contornada-nao-corrigida](../bugs/open/gl-mali-g52-r38-tela-preta-contornada-nao-corrigida_2026-08-31T19-00.md)
  — o defeito que a regra contorna, o A/B de campo, o que já foi descartado, e a pista não
  verificada de que a tela preta é **regressão do transplante 1.0.23 → 1.0.24**. **Esta task não o
  corrige**, e o registro diz isso em voz alta.
- [veredito-do-renderer-automatico-so-chega-a-relato-quando-ha-crash](../bugs/open/veredito-do-renderer-automatico-so-chega-a-relato-quando-ha-crash_2026-08-31T19-10.md)
  — a lacuna de observabilidade, que **é** corrigida aqui.

> **Correção de uma afirmação minha anterior:** eu disse que a regra entrou "sem evidência". Isso
> está errado e o registro corrige. Ela entrou **com testes** —
> `gs_gpu_driver_profile_tests.cpp` pina a string real de `GL_VERSION` do A12 através do resolvedor
> e pina que a regra **não** se alarga para r37p1, r39p0, Mali-G51 nem Mali-G57. O que faltou foi
> task e registro de bug.

### 2. Fazer o veredito chegar (o código)

A decisão do `auto` já registra o motivo (`driver-rule:<id>`, `adreno-default`,
`gl-feedback-copy-workaround`, `platform-default`) e o larga num `Console.WriteLn`. As três pontes
para um relato estão cortadas: a JNI é `void`, o resumo de boot carrega só a identidade do GPU, e o
logcat só é anexado **quando há crash**.

Tela preta e imagem com linhas **não são crash**. O único canal que carrega o veredito é o único
que não dispara na classe de defeito em que ele importa.

## Escopo

**Entra:**

- `pcsx2/GS/GSUtil.{h,cpp}` — acessor para o motivo já registrado. Puro leitor; nenhuma decisão
  gráfica muda.
- `platforms/android/.../cpp/native-lib.cpp` — **método JNI novo** devolvendo
  `"<renderer> reason=<motivo>"`.
- `NativeApp.java` — declaração do método novo.
- `MainActivityRuntime.kt` — anexar o veredito ao `setGraphicsBootSummary`, que já vai para todo
  relato.

**Não entra, e é deliberado:**

- **Tirar ou alargar a regra `gl-arm-g52-r38-auto-vulkan`.** Tirá-la devolve a tela preta —
  confirmado no A/B de campo. Alargá-la é o movimento que o plano proíbe.
- **Corrigir a tela preta em GL.** É o registro nº 1, e depende de um teste em aparelho (instalar a
  1.0.23 no A12) que decide entre "regressão nossa" e "defeito do aparelho".
- **Mudar a assinatura de `setAutoRendererGpuStrings`.** JNI liga por **nome**, não por assinatura:
  trocar `void` por `jstring` compila, linka, roda e devolve lixo em silêncio. Por isso um método
  novo, e não um retorno acrescentado.

## Como será validado

1. **Compila** — `GSUtil.cpp.o` e `native-lib.cpp.o` na árvore ninja do AGP;
   `:app:compileGithubDebugKotlin` **com `-Pkotlin.incremental=false`**.
2. **A superfície JNI não regride** — `python scripts/compare_jni_surface.py` continua sem
   divergência de assinatura, e o método novo aparece como acréscimo, não como mudança.
3. **No aparelho** — abrir um jogo e conferir que o resumo de boot passa a trazer
   `renderer=Vulkan reason=driver-rule:gl-arm-g52-r38-auto-vulkan` no A12. É a primeira vez que dá
   para provar, de fora, que a regra casou.
