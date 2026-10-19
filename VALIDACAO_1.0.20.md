# Validação do APK 1.0.20

O transporte BHTTP permanece baseado no núcleo funcional 1.0.11. Esta versão
altera somente estado da tela, padrões e encerramento do serviço:

- botão Parar interrompe o supervisor e fecha imediatamente TUN, tun2socks,
  DNS, SOCKS, SSH e bridge BHTTP;
- senha salva nas preferências privadas do aplicativo e mantida no formulário;
- IP padrão `137.131.163.233`;
- slots padrão: download 32 e upload 16;
- os slots continuam editáveis nos limites 1–32 e 1–16.

O teste host-side valida os padrões 16/32, uma configuração 7/13, os mínimos
1/1, o cálculo dinâmico de stride/bases e o vetor criptográfico BHTTP.

## APK

- `versionCode=21`, `versionName=1.0.20`;
- ABIs ARM64 e ARMv7;
- assinaturas APK v1, v2 e v3 válidas;
- SHA-256:
  `c147ac990423751a06e6e0fb4d454f7841beaf05f39dd83911c44dab64df7274`.

O servidor não foi alterado.
