package com.dtunnel.bhttptest;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Converts Lite's sparse Binary BHTTP transport into one local TCP stream.
 * The local endpoint is consumed by the SSH library exactly like ProxyCommand.
 */
public final class BhttpBridge implements AutoCloseable {
    public interface Listener {
        void log(String message);
        void fatal(Throwable error);
    }

    public interface SocketProtector {
        boolean protect(Socket socket);
    }

    private static final int MODE_PROBE = 0;
    private static final int MODE_UPLOAD = 1;
    private static final int MODE_DOWNLOAD = 2;
    private static final int MODE_BATCH = 3;
    private static final int MODE_ACK = 4;
    private static final int STATUS_OK = 0;
    private static final int STATUS_DATA = 2;
    // Functional 1.0.11 profile with the requested direction inversion:
    // eight concurrent download requests and four upload workers.
    // Sequence is a sparse response slot, never an SSH byte offset.
    private static final int BATCH_COUNT = 8;
    private static final int REFERENCE_DOWNLOAD_CHUNK_SIZE = 1399;
    private static final int REFERENCE_UPLOAD_CHUNK_SIZE = 32768;
    private static final int MIN_PROBE_CHUNK_SIZE = 10;
    private static final int CALIBRATION_TIMEOUT_MS = 3000;
    private static final int[] CHUNK_FALLBACK_STEPS = {
            1024, 768, 512, 256, 128, 64, 32, 16, MIN_PROBE_CHUNK_SIZE
    };
    private static final int[] UPLOAD_CALIBRATION_STEPS = {
            4096, 8192, 12288, 16384, 20480, 24576,
            REFERENCE_UPLOAD_CHUNK_SIZE
    };
    private static final int[] UPLOAD_LOW_FALLBACK_STEPS = {
            2048, 1024, 512, 256, 128, 64, 32, 16, MIN_PROBE_CHUNK_SIZE
    };
    private static final int DEFAULT_UPLOAD_SLOTS = 16;
    private static final int DEFAULT_DOWNLOAD_SLOTS = 32;
    private static final long UPLOAD_CALIBRATION_COOLDOWN_MS = 750;
    private static final int STATUS_FRAME_BYTES = 5;
    private static final int DATA_LENGTH_BYTES = 4;
    private static final long EMPTY_BATCH_DELAY_MS = 750;
    private static final long UPLOAD_COALESCE_MS = 5;

    private final String host;
    private final int port;
    private final Listener listener;
    private final SocketProtector protector;
    private final int uploadConnections;
    private final int downloadConnections;
    private final long windowStride;
    private final long[] windowBases;
    private final byte[] sid = new byte[16];
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean protectWarningLogged = new AtomicBoolean(false);
    private final AtomicReference<Throwable> terminalError = new AtomicReference<>();
    private final List<Socket> requestSockets =
            Collections.synchronizedList(new ArrayList<Socket>());
    private final List<Thread> threads =
            Collections.synchronizedList(new ArrayList<Thread>());
    private final Object downstreamLock = new Object();
    private final Object downloadWakeup = new Object();
    private final Map<Long, byte[]> pending = new HashMap<>();
    private final BlockingQueue<UploadFrame> uploadQueue;

    private ServerSocket localListener;
    private Socket localSocket;
    private long nextRead;
    private long nextAck = 186;
    private int downloadChunkSize = REFERENCE_DOWNLOAD_CHUNK_SIZE;
    private int uploadChunkSize = REFERENCE_UPLOAD_CHUNK_SIZE;
    private volatile long lastUploadNanos;
    private final AtomicLong uploadedBytes = new AtomicLong();
    private volatile long downloadedBytes;

    public BhttpBridge(String host, int port, Listener listener,
                       SocketProtector protector) {
        this(host, port, listener, protector,
                DEFAULT_UPLOAD_SLOTS, DEFAULT_DOWNLOAD_SLOTS);
    }

    public BhttpBridge(String host, int port, Listener listener,
                       SocketProtector protector, int uploadSlots,
                       int downloadSlots) {
        this.host = host;
        this.port = port;
        this.listener = listener;
        this.protector = protector;
        this.uploadConnections = clamp(uploadSlots, 1, 16);
        this.downloadConnections = clamp(downloadSlots, 1, 32);
        this.windowStride = (long) BATCH_COUNT * this.downloadConnections;
        this.windowBases = new long[this.downloadConnections];
        for (int index = 0; index < this.windowBases.length; index++) {
            this.windowBases[index] = (long) index * BATCH_COUNT;
        }
        this.uploadQueue = new ArrayBlockingQueue<>(this.uploadConnections * 2);
        new SecureRandom().nextBytes(sid);
    }

    public int start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("BHTTP bridge already started");
        }
        try {
            log("BHTTP SID " + hex(sid, 0, 4));
            probe(MODE_PROBE, 0);
            probe(MODE_UPLOAD, 10);
            downloadChunkSize = calibrateChunkSize();
            uploadChunkSize = calibrateUploadChunkSize();
            probe(MODE_BATCH, BATCH_COUNT);
            probe(MODE_ACK, 1);
            log("Probe BHP1: todos os modos aceitos");
            log("Perfil funcional 1.0.11 invertido: downloadConns="
                    + downloadConnections + ", uploadConns="
                    + uploadConnections + ", batch=8, download="
                    + downloadChunkSize + ", upload=" + uploadChunkSize
                    + " fixos, stride=" + windowStride);
            registerSession();
            log("Registro BHTTP seq=0 aceito");

            localListener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            Thread accept = new Thread(this::acceptLocalSsh, "BHTTP-local-accept");
            startThread(accept);
            return localListener.getLocalPort();
        } catch (Throwable error) {
            running.set(false);
            closeQuietly();
            if (error instanceof Exception) throw (Exception) error;
            throw new IOException(error);
        }
    }

    private void acceptLocalSsh() {
        try {
            localSocket = localListener.accept();
            localSocket.setTcpNoDelay(true);
            log("SSH conectado ao bridge local");
            startThread(new Thread(this::uploadReadLoop, "BHTTP-upload-reader"));
            for (int worker = 0; worker < uploadConnections; worker++) {
                startThread(new Thread(this::uploadWorkerLoop,
                        "BHTTP-upload-" + worker));
            }
            for (long base : windowBases) {
                startThread(new Thread(() -> downloadLoop(base),
                        "BHTTP-download-" + base));
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void probe(int submode, int parameter) throws Exception {
        probe(submode, parameter, 15000);
    }

    private void probe(int submode, int parameter, int timeoutMillis) throws Exception {
        byte[] clear = makeProbePayload(submode, parameter);
        byte[] encrypted = crypt(clear, MODE_PROBE, 0, false);
        Socket socket = openRequestSocket(timeoutMillis);
        try {
            sendRequest(socket, MODE_PROBE, 0, encrypted);
            Response response = readResponse(socket);
            if (response.status != STATUS_OK) {
                throw new IOException("BHP1 mode " + submode + " status " + response.status);
            }
            byte[] decoded = crypt(response.body, MODE_PROBE, 0, true);
            validateProbeResponse(decoded, submode, parameter);
        } finally {
            closeRequestSocket(socket);
        }
    }

    private static byte[] makeProbePayload(int submode, int parameter) {
        int length = submode == MODE_UPLOAD ? Math.max(10, parameter) : 10;
        byte[] clear = new byte[length];
        clear[0] = 'B'; clear[1] = 'H'; clear[2] = 'P'; clear[3] = '1';
        clear[4] = 1;
        clear[5] = (byte) submode;
        putInt(clear, 6, parameter);
        for (int index = 10; index < clear.length; index++) {
            clear[index] = (byte) ((index * 31) & 0xff);
        }
        return clear;
    }

    private static void validateProbeResponse(byte[] decoded, int submode, int parameter)
            throws IOException {
        int expectedLength = submode == MODE_DOWNLOAD ? Math.max(10, parameter) : 10;
        if (decoded.length != expectedLength || decoded[0] != 'B' || decoded[1] != 'H'
                || decoded[2] != 'P' || decoded[3] != '1' || decoded[4] != 1
                || (decoded[5] & 0xff) != submode || getInt(decoded, 6) != parameter) {
            throw new IOException("Resposta BHP1 inválida no modo " + submode
                    + ": tamanho=" + decoded.length + ", esperado=" + expectedLength);
        }
        for (int index = 10; index < decoded.length; index++) {
            if (decoded[index] != (byte) ((index * 31) & 0xff)) {
                throw new IOException("Resposta BHP1 corrompida no byte " + index);
            }
        }
    }

    /**
     * Verifies the complete eight-response batch envelope, not an isolated DATA
     * response. The known-good 1.0.5 value is the upper safety limit; calibration
     * can lower it for a narrower path but can never turn one batch into a burst
     * larger than the reference client used.
     */
    private int calibrateChunkSize() throws Exception {
        log("Calibrando envelope batch=8 do perfil 1.0.5 (chunk máximo 1399)...");

        if (acceptsChunkSize(REFERENCE_DOWNLOAD_CHUNK_SIZE)) {
            log("Calibração BHTTP: envelope completo aceito; chunk fixado em 1399 bytes");
            return REFERENCE_DOWNLOAD_CHUNK_SIZE;
        }

        int rejected = REFERENCE_DOWNLOAD_CHUNK_SIZE;
        int accepted = -1;
        for (int candidate : CHUNK_FALLBACK_STEPS) {
            if (acceptsChunkSize(candidate)) {
                accepted = candidate;
                break;
            }
            rejected = candidate;
        }
        if (accepted < 0) {
            throw new IOException("Calibração BHTTP falhou até "
                    + MIN_PROBE_CHUNK_SIZE + " bytes");
        }

        // Find the largest stable value below the rejected reference envelope.
        int low = accepted;
        int high = rejected - 1;
        while (low < high) {
            int candidate = low + ((high - low + 1) >>> 1);
            if (acceptsChunkSize(candidate)) {
                low = candidate;
            } else {
                high = candidate - 1;
            }
        }

        log("Calibração BHTTP: envelope limitado; chunk fixado em " + low
                + " bytes nesta sessão (máximo 1.0.5=1399)");
        return low;
    }

    /**
     * Calibrates upload with the same configured concurrency used by the live
     * writer. It deliberately keeps the last
     * stable tier instead of chasing the reset boundary byte by byte.
     */
    private int calibrateUploadChunkSize() throws Exception {
        log("Calibrando upload BHTTP com " + uploadConnections
                + " conexões concorrentes...");
        int accepted = -1;
        for (int candidate : UPLOAD_CALIBRATION_STEPS) {
            if (acceptsUploadChunkSize(candidate)) {
                accepted = candidate;
                continue;
            }
            if (accepted < 0) accepted = findLowUploadFallback();
            sleep(UPLOAD_CALIBRATION_COOLDOWN_MS);
            log("Calibração upload: faixa " + candidate
                    + " instável; margem operacional fixada em " + accepted
                    + " bytes com " + uploadConnections + " conexões");
            return accepted;
        }

        log("Calibração upload: perfil máximo 32768 bytes aceito com "
                + uploadConnections + " conexões");
        return REFERENCE_UPLOAD_CHUNK_SIZE;
    }

    private int findLowUploadFallback() throws Exception {
        for (int candidate : UPLOAD_LOW_FALLBACK_STEPS) {
            if (acceptsUploadChunkSize(candidate)) return candidate;
        }
        throw new IOException("Calibração de upload falhou até "
                + MIN_PROBE_CHUNK_SIZE + " bytes");
    }

    private boolean acceptsChunkSize(int candidate) throws Exception {
        return acceptsStableProbe(MODE_DOWNLOAD,
                batchEnvelopeProbeParameter(candidate), "download", candidate);
    }

    private boolean acceptsUploadChunkSize(int candidate) throws Exception {
        IOException lastError = null;
        for (int round = 1; round <= 2; round++) {
            try {
                runConcurrentUploadProbeRound(candidate);
                if (round == 2) {
                    log("Calibração upload=" + candidate
                            + " recuperada na segunda rodada concorrente");
                }
                return true;
            } catch (IOException error) {
                lastError = error;
                if (round == 1) sleep(100);
            }
        }
        log("Calibração upload=" + candidate + " recusada em 2 rodadas: "
                + concise(lastError));
        return false;
    }

    private void runConcurrentUploadProbeRound(int candidate) throws IOException {
        CountDownLatch ready = new CountDownLatch(uploadConnections);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(uploadConnections);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int index = 0; index < uploadConnections; index++) {
            Thread probeThread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    // BHP1 upload and live MODE_UPLOAD use the same 29-byte
                    // outer request header and the same request-body length.
                    probe(MODE_UPLOAD, candidate, CALIBRATION_TIMEOUT_MS);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    done.countDown();
                }
            }, "BHTTP-upload-calibration-" + index);
            probeThread.setDaemon(true);
            probeThread.start();
        }
        try {
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IOException("threads de calibração não iniciaram");
            }
            start.countDown();
            if (!done.await(12, TimeUnit.SECONDS)) {
                throw new IOException("timeout da rodada concorrente de upload");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("calibração de upload interrompida", error);
        } finally {
            start.countDown();
        }
        Throwable error = failure.get();
        if (error != null) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException(error);
        }
    }

    private boolean acceptsStableProbe(int submode, int parameter,
                                       String direction, int candidate) throws Exception {
        IOException lastError = null;
        int successStreak = 0;
        int failureStreak = 0;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                probe(submode, parameter, CALIBRATION_TIMEOUT_MS);
                successStreak++;
                failureStreak = 0;
                if (successStreak == 2) {
                    if (attempt > 2) {
                        log("Calibração " + direction + "=" + candidate
                                + " recuperada e confirmada após falha transitória");
                    }
                    return true;
                }
            } catch (IOException error) {
                lastError = error;
                failureStreak++;
                successStreak = 0;
                if (failureStreak == 2) {
                    log("Calibração " + direction + "=" + candidate
                            + " recusada 2x: " + concise(lastError));
                    return false;
                }
            }
            sleep(100);
        }
        log("Calibração " + direction + "=" + candidate + " instável: "
                + concise(lastError));
        return false;
    }

    private static int batchEnvelopeProbeParameter(int chunk) {
        // BHP1 itself has one 5-byte response frame. A real batch has eight
        // frames, each with 5 bytes of status/length plus 4 bytes of DATA length.
        long batchWireBytes = (long) BATCH_COUNT
                * (chunk + STATUS_FRAME_BYTES + DATA_LENGTH_BYTES);
        return (int) Math.max(10L, batchWireBytes - STATUS_FRAME_BYTES);
    }

    private void registerSession() throws Exception {
        Socket socket = openRequestSocket();
        try {
            sendRequest(socket, MODE_UPLOAD, 0, new byte[0]);
            Response response = readResponse(socket);
            if (response.status != STATUS_OK) {
                throw new IOException("Registro BHTTP recusado: " + response.status);
            }
        } finally {
            closeRequestSocket(socket);
        }
    }

    private void uploadReadLoop() {
        long sequence = 0;
        byte[] buffer = new byte[uploadChunkSize];
        try {
            InputStream input = new BufferedInputStream(localSocket.getInputStream());
            while (running.get()) {
                int count = input.read(buffer);
                if (count < 0) return;
                if (count == 0) continue;
                sleep(UPLOAD_COALESCE_MS);
                while (count < buffer.length && input.available() > 0) {
                    int more = input.read(buffer, count, buffer.length - count);
                    if (more <= 0) break;
                    count += more;
                }
                byte[] clear = new byte[count];
                System.arraycopy(buffer, 0, clear, 0, count);
                byte[] encrypted = crypt(clear, MODE_UPLOAD, sequence, false);
                uploadQueue.put(new UploadFrame(sequence, count, encrypted));
                sequence++;
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void uploadWorkerLoop() {
        while (running.get()) {
            try {
                UploadFrame frame = uploadQueue.take();
                int retries = 0;
                while (running.get()) {
                    try {
                        uploadFrame(frame.sequence, frame.encrypted);
                        break;
                    } catch (IOException error) {
                        if (!running.get()) return;
                        if (++retries > 10) throw error;
                        log("Retry upload seq=" + frame.sequence + " bytes="
                                + frame.clearLength + " tentativa=" + retries
                                + " erro=" + concise(error));
                        sleep(retries * 250L);
                    }
                }
                uploadedBytes.addAndGet(frame.clearLength);
                lastUploadNanos = System.nanoTime();
                synchronized (downloadWakeup) {
                    downloadWakeup.notifyAll();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable error) {
                fail(error);
                return;
            }
        }
    }

    private void uploadFrame(long sequence, byte[] encrypted) throws Exception {
        Socket socket = openRequestSocket();
        try {
            sendRequest(socket, MODE_UPLOAD, sequence, encrypted);
            Response response = readResponse(socket);
            if (response.status != STATUS_OK) {
                throw new IOException("Upload seq=" + sequence + " status=" + response.status);
            }
        } finally {
            closeRequestSocket(socket);
        }
    }

    private void downloadLoop(long initialSequence) {
        long sequence = initialSequence;
        int retries = 0;
        int emptyStreak = 0;
        while (running.get()) {
            try {
                boolean hadData = downloadBatch(sequence);
                sequence += windowStride;
                retries = 0;
                if (hadData) {
                    emptyStreak = 0;
                } else {
                    emptyStreak++;
                    boolean recentUpload = System.nanoTime() - lastUploadNanos
                            < 2_000_000_000L;
                    long delay = Math.min(EMPTY_BATCH_DELAY_MS,
                            25L << Math.min(emptyStreak, 5));
                    if (recentUpload) delay = Math.min(50L, delay);
                    waitForDownload(delay);
                }
            } catch (Throwable error) {
                if (!running.get()) return;
                retries++;
                log("Retry batch seq=" + sequence + " tentativa=" + retries
                        + " erro=" + concise(error));
                if (retries > 10) {
                    fail(error);
                    return;
                }
                sleep(Math.min(2000L, retries * 200L));
            }
        }
    }

    private boolean downloadBatch(long sequence) throws Exception {
        byte[] clear = new byte[6];
        putInt(clear, 0, downloadChunkSize);
        clear[4] = (byte) (BATCH_COUNT >>> 8);
        clear[5] = (byte) BATCH_COUNT;
        byte[] encrypted = crypt(clear, MODE_BATCH, sequence, false);
        List<Long> acknowledgements = new ArrayList<>();
        boolean hadData = false;
        Socket socket = openRequestSocket();
        try {
            sendRequest(socket, MODE_BATCH, sequence, encrypted);
            DataInputStream input = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));
            byte[][] chunks = new byte[BATCH_COUNT][];
            for (int offset = 0; offset < BATCH_COUNT; offset++) {
                Response response = readResponse(input);
                if (response.status != STATUS_DATA || response.body.length < 4) {
                    throw new IOException("Batch seq=" + (sequence + offset)
                            + " status=" + response.status);
                }
                int length = getInt(response.body, 0);
                if (length < 0 || length != response.body.length - 4) {
                    throw new IOException("Comprimento DATA inválido: " + length);
                }
                if (length == 0) {
                    chunks[offset] = new byte[0];
                } else {
                    byte[] cipher = new byte[length];
                    System.arraycopy(response.body, 4, cipher, 0, length);
                    chunks[offset] = crypt(cipher, MODE_BATCH, sequence + offset, true);
                    hadData = true;
                }
            }

            synchronized (downstreamLock) {
                for (int offset = 0; offset < BATCH_COUNT; offset++) {
                    pending.put(sequence + offset, chunks[offset]);
                }
                OutputStream output = localSocket.getOutputStream();
                while (pending.containsKey(nextRead)) {
                    byte[] chunk = pending.remove(nextRead);
                    if (chunk.length > 0) {
                        output.write(chunk);
                        output.flush();
                        downloadedBytes += chunk.length;
                    }
                    nextRead++;
                }
                while (nextRead - 1 >= nextAck) {
                    acknowledgements.add(nextAck);
                    nextAck += 187;
                }
            }
        } finally {
            closeRequestSocket(socket);
        }
        for (Long ack : acknowledgements) {
            try {
                acknowledge(ack);
            } catch (Exception error) {
                if (running.get()) {
                    // ACKs are cumulative. A later ACK frees this range too,
                    // so never replay an already-delivered DATA batch merely
                    // because this housekeeping connection failed.
                    log("ACK seq=" + ack + " adiado: " + concise(error));
                }
            }
        }
        return hadData;
    }

    private void acknowledge(long sequence) throws Exception {
        Socket socket = openRequestSocket();
        try {
            sendRequest(socket, MODE_ACK, sequence, new byte[0]);
            Response response = readResponse(socket);
            if (response.status != STATUS_OK) {
                throw new IOException("ACK seq=" + sequence + " status=" + response.status);
            }
        } finally {
            closeRequestSocket(socket);
        }
    }

    private Socket openRequestSocket() throws IOException {
        return openRequestSocket(15000);
    }

    private Socket openRequestSocket(int readTimeoutMillis) throws IOException {
        Socket socket = new Socket();
        try {
            // Materialize/bind the native descriptor before asking Android to protect it.
            // Some Android builds return false for an otherwise unbound Java Socket.
            socket.bind(new InetSocketAddress(0));
            if (protector != null && !protector.protect(socket)
                    && protectWarningLogged.compareAndSet(false, true)) {
                // This is deliberately non-fatal. DtunnelVpnService also excludes this
                // package from its VPN, which is the authoritative anti-recursion rule.
                // The reference bhttp.apk likewise does not tear down SSH when protect()
                // merely returns false.
                log("Aviso: protect(socket) retornou false; continuando porque o "
                        + "pacote está excluído da VPN");
            }
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(host, port), 8000);
            socket.setSoTimeout(readTimeoutMillis);
            requestSockets.add(socket);
            return socket;
        } catch (IOException | RuntimeException error) {
            try { socket.close(); } catch (IOException ignored) {}
            throw error;
        }
    }

    private void closeRequestSocket(Socket socket) {
        requestSockets.remove(socket);
        try { socket.close(); } catch (IOException ignored) {}
    }

    private void sendRequest(Socket socket, int mode, long sequence, byte[] payload)
            throws IOException {
        DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream()));
        sendRequest(output, mode, sequence, payload);
    }

    private void sendRequest(DataOutputStream output, int mode, long sequence,
                             byte[] payload) throws IOException {
        output.writeByte(mode);
        output.write(sid);
        output.writeLong(sequence);
        output.writeInt(payload.length);
        output.write(payload);
        output.flush();
    }

    private Response readResponse(Socket socket) throws IOException {
        return readResponse(new DataInputStream(
                new BufferedInputStream(socket.getInputStream())));
    }

    private Response readResponse(DataInputStream input) throws IOException {
        int status;
        try {
            status = input.readUnsignedByte();
        } catch (EOFException error) {
            throw new IOException("Resposta BHTTP truncada", error);
        }
        int length = input.readInt();
        if (length < 0 || length > 2 * 1024 * 1024) {
            throw new IOException("Resposta BHTTP grande/inválida: " + length);
        }
        byte[] body = new byte[length];
        input.readFully(body);
        return new Response(status, body);
    }

    private byte[] crypt(byte[] input, int mode, long sequence, boolean response)
            throws Exception {
        byte[] output = new byte[input.length];
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer seed = ByteBuffer.allocate(30).order(ByteOrder.BIG_ENDIAN);
        seed.put(sid);
        seed.put((byte) mode);
        seed.putLong(sequence);
        seed.put((byte) (response ? 1 : 0));
        int offset = 0;
        int block = 0;
        while (offset < input.length) {
            seed.position(26);
            seed.putInt(block++);
            byte[] key = digest.digest(seed.array());
            int count = Math.min(32, input.length - offset);
            for (int index = 0; index < count; index++) {
                output[offset + index] = (byte) (input[offset + index] ^ key[index]);
            }
            offset += count;
        }
        return output;
    }

    private void startThread(Thread thread) {
        thread.setDaemon(true);
        threads.add(thread);
        thread.start();
    }

    private void fail(Throwable error) {
        if (!running.get()) return;
        if (terminalError.compareAndSet(null, error)) {
            listener.fatal(error);
        }
    }

    public long getUploadedBytes() { return uploadedBytes.get(); }
    public long getDownloadedBytes() { return downloadedBytes; }

    @Override
    public void close() {
        running.set(false);
        closeQuietly();
    }

    private void closeQuietly() {
        uploadQueue.clear();
        synchronized (downloadWakeup) {
            downloadWakeup.notifyAll();
        }
        try { if (localListener != null) localListener.close(); } catch (IOException ignored) {}
        try { if (localSocket != null) localSocket.close(); } catch (IOException ignored) {}
        synchronized (requestSockets) {
            for (Socket socket : new ArrayList<>(requestSockets)) {
                try { socket.close(); } catch (IOException ignored) {}
            }
            requestSockets.clear();
        }
        synchronized (threads) {
            for (Thread thread : threads) thread.interrupt();
        }
    }

    private void log(String message) { listener.log(message); }
    private void waitForDownload(long millis) {
        synchronized (downloadWakeup) {
            if (!running.get()) return;
            try {
                downloadWakeup.wait(millis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
    private static String concise(Throwable error) {
        String text = error.getMessage();
        return text == null ? error.getClass().getSimpleName() : text;
    }
    private static int getInt(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
    }
    private static void putInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
    private static String hex(byte[] data, int offset, int count) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < count; index++) {
            builder.append(String.format("%02x", data[offset + index] & 0xff));
        }
        return builder.toString();
    }

    private static final class Response {
        final int status;
        final byte[] body;
        Response(int status, byte[] body) { this.status = status; this.body = body; }
    }

    private static final class UploadFrame {
        final long sequence;
        final int clearLength;
        final byte[] encrypted;

        UploadFrame(long sequence, int clearLength, byte[] encrypted) {
            this.sequence = sequence;
            this.clearLength = clearLength;
            this.encrypted = encrypted;
        }
    }
}
