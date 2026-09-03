# Bugs aplicáveis ao ARMSX2-fork

Esta pasta contém **20 relatórios** cuja causa afeta ou continua presente na árvore atual.

O status, a viabilidade de correção, a severidade e a evidência de cada item estão no
[`README` da triagem](../README.md#armsx2-fork-atual).

Dois relatórios nasceram na linha antiga, mas ficam aqui porque a mesma causa foi confirmada no
código atual: carga de `NativeApp` antes do worker e `getExternalFilesDir()` no caminho da UI.
