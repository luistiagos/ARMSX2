# Bugs aplicáveis ao ARMSX2-fork

Esta pasta contém **12 relatórios** cuja causa afeta ou continua presente na árvore atual.

O status, a viabilidade de correção, a severidade e a evidência de cada item estão no
[`README` da triagem](../README.md#armsx2-fork-atual).

Os dois relatórios que tinham nascido na linha antiga e ficavam aqui por causa confirmada no código
atual — carga de `NativeApp` antes do worker e `getExternalFilesDir()` no caminho da UI — foram
corrigidos e validados em aparelho na
[TASK-0079](../../../task/TASK-0079-boot-nao-toca-o-nativo-nem-o-disco-na-ui.md).
