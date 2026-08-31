# TASK-0055: fazer o `PerfLog` dizer a verdade sobre EE, GS, VU e GPU

- **Status:** em andamento
- **Criada em:** 2026-08-30
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum
- **Backlog:** item 0 de [`desempenho-com-clock-cortado-a55`](../backlog/desempenho-com-clock-cortado-a55.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0055:` no assunto)
- **Revertida por:** —
- **Publicado em:** —


> ## Validada no A12 em 2026-08-30 — e a hipótese central estava errada
>
> O que esta task supunha: os `EE 0% GS 0% VU 0%` vinham de `get_thread_time()` falhando em
> silêncio. Para provar ou derrubar isso, ela adicionou um aviso de uma linha.
>
> **O aviso não disparou.** O relógio por thread lê perfeitamente — e foi esse silêncio que apontou
> para o outro lado da conta, o divisor. A causa real é `CNTFRQ_EL0` lendo 0
> ([TASK-0060](TASK-0060-relogio-de-ticks-quando-cntfrq-le-zero.md)): em `double`,
> `100.0 * (1.0 / (x / 0.0))` é `100 * (1/+inf)` = **`0.0`**, que é o `0%` impresso.
>
> Depois da TASK-0060, com o código desta task no lugar:
>
> ```
> antes:   PerfLog: 25.6 fps | EE 0%   GS 0%  VU 0% GPU 0% | frame 771
> depois:  PerfLog: 25.0 fps | EE 100% GS 37% VU 0% GPU 0% | frame 758
> ```
>
> **O que fica desta task, e vale:** o relógio por TID (uma dependência a menos da lista de threads
> da libc), o aviso — que fez o seu trabalho ao **não** disparar —, e o `n/a`/campo omitido, que é o
> que impede o próximo zero de se passar por medição.
>
> **`GPU 0%` sobreviveu à correção**, e portanto é outro defeito: o campo é impresso, logo
> `SetGPUTimingAvailable(true)` foi chamado e o backend aceita timestamp query — mas não produz
> tempo de GPU. Isolado, e em aberto.

## Contexto

Toda linha de `PerfLog` colhida na investigação do teto de clock do GOS sai assim, tanto a 8 fps
quanto a 50 fps:

```
PerfLog: 18.8 fps | EE 0% GS 0% VU 0% GPU 0% | frame 565
PerfLog: 49.8 fps | EE 0% GS 0% VU 0% GPU 0% | frame 3646
```

O `fps` está certo; os quatro percentuais, não. Enquanto isso não muda, não dá para saber se o
gargalo é EE, GS ou GPU — nem provar que uma otimização ajudou.

A linha é impressa **dentro do núcleo**, em `PerformanceMetrics::Update()`
(`pcsx2/PerformanceMetrics.cpp:405`), não pela ponte JNI. O backlog aponta
`Java_kr_co_iefriends_pcsx2_NativeApp_getCpuThreadUsage` e vizinhos
(`native-lib.cpp:889`) como "onde olhar": esses wrappers estão corretos e não participam do
`PerfLog`. Corrigido no backlog nesta task.

### São dois defeitos, não um

**EE / GS / VU** vêm de `ThreadHandle::GetCPUTime()`, e o caminho POSIX
(`common/Linux/LnxThreads.cpp`) devolve **`0` em toda falha**:

```cpp
static u64 get_thread_time(uptr id = 0)
{
	clockid_t cid;
	if (id)
	{
		int err = pthread_getcpuclockid((pthread_t)id, &cid);
		if (err)
			return 0;          // <-- indistinguível de "thread ociosa"
	}
	...
	if (err)
		return 0;              // <-- idem
	return (u64)ts.tv_sec * (u64)1e6 + (u64)ts.tv_nsec / (u64)1e3;
}
```

Uma leitura constante em 0 produz delta 0 a cada janela, e delta 0 imprime `0%`. É o **único**
caminho que explica os três contadores de CPU zerados ao mesmo tempo enquanto o `fps` da mesma
função sai certo — e é silencioso por construção: o valor de falha é um número plausível.

**GPU** é outra coisa. `s_gpu_usage` vem de `PerformanceMetrics::OnGPUPresent()`, alimentado por
`GSDevice::GetAndResetAccumulatedGPUTime()`, que devolve `0.0f` fixo quando o backend não tem
timestamp query. No Vulkan isso é
`m_gpu_timing_supported = (limits.timestampComputeAndGraphics != 0 && ...)`
(`GSDeviceVK.cpp:893`); `GS.cpp:181` já trata a recusa (`SetGPUTimingEnabled(true)` falso →
`GSConfig.OsdShowGPU = false`) e depois ninguém mais é avisado. Um Mali sem timestamp válido
imprime `GPU 0%` para sempre, e isso não é medição — é ausência de medição.

### A função já tem a convenção certa, três linhas acima

```cpp
// The back thread only exists under GSBackThreadMode >= Lockstep, so the field is
// omitted rather than logged as a permanent 0% in the default configuration.
char gs_back[32] = {};
if (HasGSBackThread())
	std::snprintf(gs_back, sizeof(gs_back), " GSB %.0f%%", s_gs_back_thread_usage);
```

Um contador que não existe **não é impresso**. Esta task estende a mesma regra aos outros quatro.

## Objetivo

Que o `PerfLog` mostre números reais quando o relógio existe, e diga `n/a` quando não existe —
nunca `0%` para "não sei medir".

## Escopo

**Entra:**

- `common/Linux/LnxThreads.cpp` — `ThreadHandle::GetCPUTime()` passa a pedir o relógio da thread
  **pelo TID** (`m_native_id`, que o handle já guarda no Linux), com o mesmo encoding que o
  `pthread_getcpuclockid()` devolveria (`MAKE_THREAD_CPUCLOCK`), caindo no caminho antigo se
  falhar. Some a dependência do `pthread_t` continuar registrado na lista de threads da libc.
  Na primeira falha, uma linha de log com `errno` — o valor de retorno continua 0, mas deixa de
  ser mudo.
- `pcsx2/PerformanceMetrics.{h,cpp}` — `SetGPUTimingAvailable()`/`HasGPUTiming()`, e o `PerfLog`
  passa a omitir o campo `GPU` quando não há timing, e a imprimir `EE n/a GS n/a VU n/a` quando o
  relógio por thread não pôde ser lido.
- `pcsx2/GS/GS.cpp` — o único ponto que já sabe a resposta (`SetGPUTimingEnabled(true)`) informa
  o `PerformanceMetrics`.

**NÃO entra:**

- **Acessor `IsGPUTimingEnabled()` no `GSDevice`.** Seriam seis backends tocados (DX11, DX12,
  Metal, Null, OGL, VK) para um dado que `GS.cpp:181` já tem na mão. Divergência do core em troca
  de nada.
- **Provar qual das duas chamadas POSIX falha no A12.** Não é determinável sem o aparelho; a
  linha de log desta task é exatamente o instrumento que responde na próxima execução.
- **Expor os contadores na OSD do Android.** A ponte JNI já existe e está correta; quem consome é
  outra decisão de produto.
- Os itens 1, 2 e 3 do backlog — TASK-0056, TASK-0057 e TASK-0058.

## Como validar

Com um jogo rodando, no `emulog.txt`:

1. As linhas de `PerfLog` deixam de trazer `EE 0% GS 0% VU 0%`. Num jogo pesado de CPU o `EE`
   sobe; num pesado de GPU o `GS` sobe. Somados, os três ficam coerentes com o `top` por thread do
   protocolo de medição do backlog.
2. Se o relógio por thread **não** puder ser lido, aparece uma vez
   `PerformanceMetrics: thread CPU clock unavailable (errno N)` e as linhas seguintes trazem
   `EE n/a GS n/a VU n/a` — o defeito passa a ser legível em vez de silencioso.
3. Se o backend não tiver timestamp query, o campo `GPU` **some** da linha, como já acontece com
   `GSB`.

O critério que fecha a task é (1) num aparelho e (2) ou (3) sendo o que aparece no outro — não
`0%` em nenhum dos dois.
