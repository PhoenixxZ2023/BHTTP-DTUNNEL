package com.dtunnel.bhttptest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.ConnectionInfo;
import com.trilead.ssh2.ConnectionMonitor;
import com.trilead.ssh2.DynamicPortForwarder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class DtunnelVpnService extends VpnService {
    public static final String ACTION_START = "com.dtunnel.bhttptest.START";
    public static final String ACTION_STOP = "com.dtunnel.bhttptest.STOP";
    public static final String ACTION_EVENT = "com.dtunnel.bhttptest.EVENT";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_DNS = "dns";
    public static final String EXTRA_UPLOAD_SLOTS = "upload_slots";
    public static final String EXTRA_DOWNLOAD_SLOTS = "download_slots";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_STATE = "state";

    private static final String TAG = "DT-BHTTP-VPN";
    private static final String CHANNEL_ID = "dtunnel_test_vpn";
    private static final int NOTIFICATION_ID = 7401;
    private static final Object LOG_LOCK = new Object();
    private static final StringBuilder LOG_BUFFER = new StringBuilder();
    private static final int MAX_LOG_CHARS = 200000;

    private volatile boolean desired;
    private Thread supervisor;
    private volatile CountDownLatch failureSignal;
    private String host;
    private int port;
    private String user;
    private String password;
    private String dnsServer;
    private int uploadSlots;
    private int downloadSlots;

    private BhttpBridge bridge;
    private Connection ssh;
    private DynamicPortForwarder socks;
    private DnsProxy dnsProxy;
    private ParcelFileDescriptor tun;
    private Process tun2socks;
    private File tunSocketFile;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            event("Parando VPN...", "stopping");
            desired = false;
            signalFailure();
            Thread activeSupervisor = supervisor;
            if (activeSupervisor != null) activeSupervisor.interrupt();
            // Close immediately as well as from the supervisor's finally block.
            // This makes Stop effective even while connect/auth is blocked.
            closePipeline();
            stopForegroundCompat();
            event("VPN desconectada", "stopped");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;
        if (desired) {
            event("VPN já está iniciada", "connected");
            return START_NOT_STICKY;
        }

        host = intent.getStringExtra(EXTRA_HOST);
        port = intent.getIntExtra(EXTRA_PORT, 80);
        user = intent.getStringExtra(EXTRA_USER);
        password = intent.getStringExtra(EXTRA_PASSWORD);
        dnsServer = intent.getStringExtra(EXTRA_DNS);
        uploadSlots = intent.getIntExtra(EXTRA_UPLOAD_SLOTS, 16);
        downloadSlots = intent.getIntExtra(EXTRA_DOWNLOAD_SLOTS, 32);
        if (host == null || host.trim().isEmpty() || user == null || user.isEmpty()
                || password == null || password.isEmpty()
                || dnsServer == null || dnsServer.isEmpty()) {
            event("Host, usuário, senha e DNS são obrigatórios", "error");
            stopSelf();
            return START_NOT_STICKY;
        }

        clearRecordedLogs();
        desired = true;
        startForeground(NOTIFICATION_ID, notification("Conectando..."));
        supervisor = new Thread(this::supervise, "DT-VPN-supervisor");
        supervisor.start();
        return START_NOT_STICKY;
    }

    private void supervise() {
        int reconnect = 0;
        while (desired) {
            try {
                failureSignal = new CountDownLatch(1);
                establishPipeline();
                reconnect = 0;
                event("VPN conectada e roteando tráfego", "connected");
                long lastUp = bridge.getUploadedBytes();
                long lastDown = bridge.getDownloadedBytes();
                long lastSample = System.nanoTime();
                long nextKeepalive = lastSample + TimeUnit.SECONDS.toNanos(30);
                updateNotification(speedText(0, 0, lastUp, lastDown));

                while (desired) {
                    if (failureSignal.await(1, TimeUnit.SECONDS)) {
                        if (desired) throw new Exception("transporte sinalizou desconexão");
                        break;
                    }
                    long now = System.nanoTime();
                    long currentUp = bridge.getUploadedBytes();
                    long currentDown = bridge.getDownloadedBytes();
                    double seconds = Math.max(0.001,
                            (now - lastSample) / 1_000_000_000.0);
                    long upPerSecond = Math.max(0,
                            Math.round((currentUp - lastUp) / seconds));
                    long downPerSecond = Math.max(0,
                            Math.round((currentDown - lastDown) / seconds));
                    updateNotification(speedText(
                            upPerSecond, downPerSecond, currentUp, currentDown));
                    lastUp = currentUp;
                    lastDown = currentDown;
                    lastSample = now;

                    if (now >= nextKeepalive) {
                        long latency = ssh.ping();
                        log("Keepalive SSH OK: " + latency + " ms; up="
                                + currentUp + "B down=" + currentDown + "B");
                        nextKeepalive = now + TimeUnit.SECONDS.toNanos(30);
                    }
                }
            } catch (Throwable error) {
                if (desired) {
                    reconnect++;
                    log("Conexão perdida: " + concise(error));
                    event("Reconectando em 5s (tentativa " + reconnect + ")", "reconnecting");
                }
            } finally {
                closePipeline();
            }
            if (desired) sleep(5000);
        }
        event("VPN desconectada", "stopped");
        stopForegroundCompat();
        stopSelf();
    }

    private void establishPipeline() throws Exception {
        event("Testando probe BHP1...", "connecting");
        log("Slots BHTTP: download=" + downloadSlots + ", upload=" + uploadSlots);
        bridge = new BhttpBridge(host, port, new BhttpBridge.Listener() {
            @Override public void log(String message) { DtunnelVpnService.this.log(message); }
            @Override public void fatal(Throwable error) {
                DtunnelVpnService.this.log("BHTTP fatal: " + concise(error));
                signalFailure();
            }
        }, this::protectBhttpSocket, uploadSlots, downloadSlots);
        int localPort = bridge.start();

        event("Negociando SSH...", "connecting");
        ssh = new Connection("127.0.0.1", localPort);
        ssh.addConnectionMonitor(new ConnectionMonitor() {
            @Override public void connectionLost(Throwable reason) {
                log("SSH encerrado: " + concise(reason));
                signalFailure();
            }
            @Override public void onReceiveInfo(int type, String message) {
                if (type == 101) log("Banner SSH: " + message);
                else log("SSH info " + type + ": " + message);
            }
        });
        ConnectionInfo info = ssh.connect((server, serverPort, algorithm, key) -> {
            log("Host key aceita para teste: " + algorithm + " (" + key.length + "B)");
            return true;
        }, 10000, 20000);
        log("KEX=" + info.keyExchangeAlgorithm
                + " cipher=" + info.serverToClientCryptoAlgorithm
                + " mac=" + info.serverToClientMACAlgorithm);

        event("Autenticando via PAM...", "authenticating");
        if (!ssh.authenticateWithPassword(user, password)) {
            throw new Exception("OpenSSH/PAM recusou usuário ou senha");
        }
        log("Autenticação SSH/PAM concluída");

        int socksPort = findFreeTcpPort();
        socks = ssh.createDynamicPortForwarder(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), socksPort));
        log("SOCKS5 SSH em 127.0.0.1:" + socksPort);

        dnsProxy = new DnsProxy(socksPort, dnsServer, this::log);
        log("DNS " + dnsServer + ":53 via TCP/SOCKS/SSH; gateway local 127.0.0.1:"
                + dnsProxy.getPort());
        dnsProxy.testUpstream();
        log("Teste DNS/TCP via SSH concluído");

        Builder builder = new Builder()
                .setSession("Bilola Tunnel")
                .setMtu(1500)
                .addAddress("10.10.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(dnsServer);
        // Same behavior as bhttp.apk on Android 10+: do not let Data Saver or
        // per-app metered restrictions suppress traffic through this VPN.
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
        // Prevent the outer BHTTP connection from recursively entering this VPN.
        builder.addDisallowedApplication(getPackageName());
        tun = builder.establish();
        if (tun == null) throw new Exception("VpnService.Builder.establish retornou null");
        log("Interface TUN estabelecida: 10.10.0.2/24 MTU 1500 DNS " + dnsServer);

        startTun2Socks(socksPort, dnsProxy.getPort());
        passTunFileDescriptor(tun.getFileDescriptor());
        sleep(300);
        if (tun2socks == null || !tun2socks.isAlive()) {
            throw new Exception("tun2socks encerrou após receber o TUN");
        }
        log("tun2socks ativo e descritor TUN aceito");
    }

    private boolean protectBhttpSocket(Socket socket) {
        return protect(socket);
    }

    private void startTun2Socks(int socksPort, int dnsPort) throws Exception {
        File executable = new File(getApplicationInfo().nativeLibraryDir, "libtun2socks.so");
        if (!executable.isFile()) throw new Exception("libtun2socks.so não encontrado");
        tunSocketFile = new File(getFilesDir(), "sock_path");
        if (tunSocketFile.exists() && !tunSocketFile.delete()) {
            throw new Exception("não foi possível limpar sock_path anterior");
        }

        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.add("--netif-ipaddr"); command.add("10.10.0.2");
        command.add("--netif-netmask"); command.add("255.255.255.0");
        command.add("--socks-server-addr"); command.add("127.0.0.1:" + socksPort);
        command.add("--tunmtu"); command.add("1500");
        command.add("--sock"); command.add("sock_path");
        command.add("--dnsgw"); command.add("127.0.0.1:" + dnsPort);
        command.add("--loglevel"); command.add("notice");

        tun2socks = new ProcessBuilder(command)
                .directory(getFilesDir())
                .redirectErrorStream(true)
                .start();
        Thread output = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(tun2socks.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) log("tun2socks: " + line);
            } catch (Throwable error) {
                if (desired) log("tun2socks log: " + concise(error));
            }
        }, "DT-tun2socks-log");
        output.setDaemon(true);
        output.start();

        Thread watcher = new Thread(() -> {
            try {
                int exit = tun2socks.waitFor();
                if (desired) log("tun2socks terminou, código=" + exit);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                signalFailure();
            }
        }, "DT-tun2socks-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void passTunFileDescriptor(FileDescriptor descriptor) throws Exception {
        Throwable last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(
                        tunSocketFile.getAbsolutePath(),
                        LocalSocketAddress.Namespace.FILESYSTEM));
                socket.setFileDescriptorsForSend(new FileDescriptor[]{descriptor});
                socket.getOutputStream().write(0x2a);
                socket.getOutputStream().flush();
                log("Descritor TUN entregue ao tun2socks");
                return;
            } catch (Throwable error) {
                last = error;
                sleep(50L << attempt);
            }
        }
        throw new Exception("falha ao entregar FD ao tun2socks", last);
    }

    private int findFreeTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1,
                InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private synchronized void closePipeline() {
        Process process = tun2socks; tun2socks = null;
        if (process != null) process.destroy();
        if (tunSocketFile != null && tunSocketFile.exists()) tunSocketFile.delete();
        tunSocketFile = null;
        try { if (tun != null) tun.close(); } catch (Throwable ignored) {}
        tun = null;
        try { if (dnsProxy != null) dnsProxy.close(); } catch (Throwable ignored) {}
        dnsProxy = null;
        try { if (socks != null) socks.close(); } catch (Throwable ignored) {}
        socks = null;
        try { if (ssh != null) ssh.close(); } catch (Throwable ignored) {}
        ssh = null;
        try { if (bridge != null) bridge.close(); } catch (Throwable ignored) {}
        bridge = null;
        // Never retain the PAM password after the requested connection stops.
        if (!desired) password = null;
    }

    private void signalFailure() {
        CountDownLatch signal = failureSignal;
        if (signal != null) signal.countDown();
    }

    private void event(String message, String state) {
        Log.i(TAG, message);
        recordLog(message);
        Intent event = new Intent(ACTION_EVENT).setPackage(getPackageName());
        event.putExtra(EXTRA_MESSAGE, message);
        event.putExtra(EXTRA_STATE, state);
        sendBroadcast(event);
    }

    private void log(String message) {
        Log.i(TAG, message);
        recordLog(message);
        Intent event = new Intent(ACTION_EVENT).setPackage(getPackageName());
        event.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(event);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Bilola Tunnel")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, notification(text));
    }

    private static String speedText(long upPerSecond, long downPerSecond,
                                    long totalUp, long totalDown) {
        return "↓ " + formatBytes(downPerSecond) + "/s  ↑ "
                + formatBytes(upPerSecond) + "/s  •  "
                + formatBytes(totalDown + totalUp);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes / 1024.0;
        if (value < 1024) return String.format(Locale.US, "%.1f KB", value);
        value /= 1024.0;
        if (value < 1024) return String.format(Locale.US, "%.1f MB", value);
        return String.format(Locale.US, "%.2f GB", value / 1024.0);
    }

    private static void recordLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (LOG_LOCK) {
            LOG_BUFFER.append(time).append("  ").append(message).append('\n');
            if (LOG_BUFFER.length() > MAX_LOG_CHARS) {
                LOG_BUFFER.delete(0, LOG_BUFFER.length() - MAX_LOG_CHARS);
            }
        }
    }

    private static void clearRecordedLogs() {
        synchronized (LOG_LOCK) {
            LOG_BUFFER.setLength(0);
        }
    }

    public static String getLogSnapshot() {
        synchronized (LOG_LOCK) {
            return LOG_BUFFER.toString();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Bilola Tunnel", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @Override
    public void onRevoke() {
        desired = false;
        signalFailure();
        closePipeline();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        desired = false;
        signalFailure();
        closePipeline();
        super.onDestroy();
    }

    private static String concise(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
