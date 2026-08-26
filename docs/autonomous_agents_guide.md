# Guia de Suporte e Configuração do RetroSystem PS2 (ARMSX2) para Agentes de Atendimento

Este guia foi elaborado especificamente para orientar os agentes autônomos (IA de vendas e suporte) a responder dúvidas de clientes sobre o emulador **RetroSystem PS2** (ID de pacote: `come.nanodata.armsx2`). 

O foco deste documento é fornecer respostas rápidas, práticas e focadas na experiência do usuário para resolver problemas de desempenho (FPS), bugs gráficos, erros de download e sugerir a configuração ideal para cada tipo de celular.

---

## 1. Funcionamento Básico: BIOS e Jogos 100% Automáticos

A principal vantagem do RetroSystem PS2 é a facilidade de uso (Plug and Play). O agente deve deixar isso muito claro para os clientes:

* **BIOS Automática**: O usuário não precisa procurar, baixar ou configurar arquivos de BIOS na internet. O aplicativo extrai e configura a BIOS automaticamente no primeiro boot.
* **Catálogo de Jogos Integrado**: O emulador possui uma aba de "Catálogo" onde o usuário escolhe qualquer um dos jogos disponíveis e clica para baixar. O download é feito 100% dentro do app em formato compacto (`.chd`), e o jogo aparece pronto para jogar na tela inicial ("Meus Jogos") assim que termina.

---

## 2. Resolução de Dúvidas de Usuários (Troubleshooting)

### 2.1 Como melhorar o FPS (Jogo travando ou lento)
Se o cliente reclamar de lentidão ou lag no celular dele, oriente-o com os seguintes passos:

1. **Alterar o Preset de Performance**:
   - Vá em **Configurações > Performance**.
   - No topo, selecione o preset **"Melhor desempenho"** (Best performance). Isso ajustará automaticamente a resolução do emulador para `1.0x` (resolução original do PS2), desativará filtros pesados e otimizará o uso de hardware.
2. **Ativar o Underclock da CPU do PS2 (EE Cycle Rate)**:
   - Se o jogo continuar lento em celulares mais antigos, oriente o usuário a alterar o ajuste de **EECycleRate** para valores negativos (como `-1` ou `-2`) nas configurações de emulação.
   - *Como funciona:* Isso reduz a carga sobre a CPU do celular do usuário. O jogo pode ter leves pulos de áudio, mas rodará com um ganho expressivo de FPS.
3. **Verificar os Speedhacks**:
   - Garanta que a opção **VU Thread** (Vector Unit 1 em thread separada) esteja ativada nas configurações de performance. Ela permite que celulares com múltiplos núcleos processem a física e os gráficos em paralelo.

---

### 2.2 Como resolver Glitches Gráficos (Erros visuais, tela piscando, cores erradas)
Se o usuário notar sombras pretas no chão, personagens sem textura, tela piscando ou falhas de imagem:

1. **Mudar a API de Renderização (Renderer)**:
   - Vá em **Configurações > Gráficos**.
   - Altere a opção de **Renderer** de **Auto** ou **Vulkan** para **OpenGL** (ou vice-versa).
   - *Explicação:* Alguns jogos rodam perfeitamente em Vulkan, enquanto outros (especialmente em chips de celular Mali/PowerVR) exigem OpenGL para renderizar os efeitos 3D corretamente.
2. **Ajustar a Precisão de Mistura (Accurate Blending Unit)**:
   - Se houver problemas com fumaça, neblina ou sombras (ex.: *Silent Hill* ou *GTA*), oriente a mudar a precisão de mesclagem para **Medium** ou **High** nas configurações de gráficos.
3. **Manter o HW Download Mode em "Enabled"**:
   - Alguns jogos leem texturas da tela para criar efeitos de reflexo ou desfoque. Desativar isso causa bugs gráficos graves. Mantenha essa opção em **Enabled** (Ativado).

---

### 2.3 Erro ao baixar jogos (Download trava ou não inicia)
Se o download de algum jogo do catálogo falhar ou ficar pausado:

1. **Verificar o Espaço de Armazenamento**:
   - Os jogos de PS2 são grandes (variam de 1 GB a 4 GB mesmo compactados). O usuário precisa de pelo menos o dobro de espaço livre do tamanho do jogo para que o download e a descompactação ocorram sem erros.
2. **Pausar e Retomar o Download**:
   - O gerenciador de downloads suporta retomar conexões interrompidas. Se a internet oscilar, o usuário pode clicar em pausar e depois em retomar para continuar de onde parou.
3. **Limpar o Cache**:
   - Se as capas dos jogos ou a lista do catálogo sumirem devido a erros de rede, oriente a fechar o aplicativo e abri-lo novamente com uma conexão de internet estável.

---

## 3. Configurações Ideais Recomendadas por Dispositivo

Quando um cliente perguntar se o jogo vai rodar no celular dele, o agente deve orientá-lo com base no processador/marca do aparelho:

### 3.1 Celulares de Entrada / Mais Antigos (Ex: Snapdragon série 400/600 antigos, processadores MediaTek básicos, GPUs Mali/PowerVR)
* **Configuração Ideal**:
  * **Renderer (Gráficos)**: OpenGL (mais estável e compatível para processadores mais simples).
  * **Preset de Desempenho**: **Melhor Desempenho** (resolução em `1.0x` para evitar sobrecarga térmica e travamentos).
  * **Ajuste Adicional**: Definir **EECycleRate** em `-1` para dar fôlego ao processador.

### 3.2 Celulares Intermediários (Ex: Snapdragon série 600 modernos / série 700, chipsets MediaTek Helio G90/Dimensity intermediários)
* **Configuração Ideal**:
  * **Renderer (Gráficos)**: Vulkan (ou Auto, que selecionará Vulkan se a GPU for Adreno 6xx+).
  * **Preset de Desempenho**: **Equilibrado** (resolução em `2.0x` para imagem nítida com boa taxa de quadros).

### 3.3 Celulares Top de Linha (Ex: Snapdragon 8 Gen 1/2/3, Adreno 7xx, Samsung Galaxy com Xclipse/Exynos modernos)
* **Configuração Ideal**:
  * **Renderer (Gráficos)**: Vulkan/Auto.
  * **Preset de Desempenho**: **Melhor Qualidade** (resolução em `3.0x` ou superior, com filtro de nitidez AMD CAS e anti-aliasing FXAA ativados para gráficos de console moderno).
  * **Filtros Adicionais**: Habilitar Mipmapping e Anisotropia em `4x`.
