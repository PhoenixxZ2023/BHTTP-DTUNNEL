# Referência extraída do bhttp.apk

Referência analisada:

```text
package: com.dtunnel.lite
versionName: 4.6.6
versionCode: 28
VPN service: com.ssh.service.SshVpnService
```

Elementos usados no cliente de teste:

- permissão `android.permission.BIND_VPN_SERVICE`;
- execução do arquivo `nativeLibraryDir/libtun2socks.so`;
- `--netif-ipaddr 10.10.0.2`;
- `--netif-netmask 255.255.255.0`;
- `--socks-server-addr 127.0.0.1:<porta>`;
- `--tunmtu 1500`;
- `--sock sock_path`;
- opção `--dnsgw`;
- `--loglevel notice`;
- LocalSocket no namespace `FILESYSTEM`;
- `setFileDescriptorsForSend(new FileDescriptor[]{tunFd})`;
- envio do byte `0x2a` após anexar o descritor;
- seis tentativas de entrega do FD com backoff exponencial iniciado em 50 ms;
- autenticação SSH e proxy dinâmico/direct-tcpip antes de iniciar o TUN.

O APK de referência foi preservado sem alterações. Os binários ARM de
`tun2socks` são reutilizados para que o comportamento de encaminhamento de
pacotes seja o mesmo do aplicativo funcional.

