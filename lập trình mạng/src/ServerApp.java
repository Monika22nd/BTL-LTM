import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * T45 - HTTP server minh hoa ba mo hinh:
 * single-thread, thread-per-connection va fixed thread pool.
 */
public final class ServerApp {
    private ServerApp() {
    }

    public static void main(String[] args) {
        try {
            Config config = Config.from(args);
            HttpServer server = switch (config.mode) {
                case SINGLE -> new SingleThreadServer(config.port);
                case THREAD -> new ThreadPerConnectionServer(config.port);
                case POOL -> new ThreadPoolServer(
                        config.port, config.poolSize, config.queueSize);
            };

            Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "shutdown-hook"));
            printBanner(config);
            server.start();
        } catch (IllegalArgumentException exception) {
            System.err.println("Loi cau hinh: " + exception.getMessage());
            System.err.println("Dung: --mode=single|thread|pool --port=8080 "
                    + "--pool-size=4 --queue-size=100");
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Khong the khoi dong server: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static void printBanner(Config config) {
        System.out.println("T45 Java HTTP Server");
        System.out.println("Mode       : " + config.mode.cliName);
        System.out.println("Address    : http://localhost:" + config.port + "/");
        System.out.println("Slow route : http://localhost:" + config.port + "/sleep?ms=1000");
        System.out.println("Metrics    : http://localhost:" + config.port + "/metrics");
        if (config.mode == Mode.POOL) {
            System.out.println("Pool/queue : " + config.poolSize + "/" + config.queueSize);
        }
        System.out.println("Nhan Ctrl+C de dung server.");
    }

    private enum Mode {
        SINGLE("single"), THREAD("thread"), POOL("pool");

        private final String cliName;

        Mode(String cliName) {
            this.cliName = cliName;
        }

        private static Mode from(String value) {
            for (Mode mode : values()) {
                if (mode.cliName.equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Mode phai la single, thread hoac pool.");
        }
    }

    private record Config(Mode mode, int port, int poolSize, int queueSize) {
        private static Config from(String[] args) {
            Map<String, String> options = parseOptions(args);
            Mode mode = Mode.from(options.getOrDefault("mode", "pool"));
            int port = positiveInt(options, "port", 8080);
            int poolSize = positiveInt(options, "pool-size", 4);
            int queueSize = positiveInt(options, "queue-size", 100);
            if (port > 65_535) {
                throw new IllegalArgumentException("Port phai nam trong khoang 1..65535.");
            }
            return new Config(mode, port, poolSize, queueSize);
        }

        private static Map<String, String> parseOptions(String[] args) {
            Map<String, String> values = new HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw new IllegalArgumentException("Tham so sai dinh dang: " + arg);
                }
                String[] pair = arg.substring(2).split("=", 2);
                values.put(pair[0], pair[1]);
            }
            return values;
        }

        private static int positiveInt(Map<String, String> values, String name, int fallback) {
            try {
                int value = Integer.parseInt(values.getOrDefault(name, String.valueOf(fallback)));
                if (value <= 0) {
                    throw new NumberFormatException();
                }
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("--" + name + " phai la so nguyen duong.");
            }
        }
    }

    /** Phan chung: mo cong, accept connection va dong server. */
    private abstract static class HttpServer {
        protected final int port;
        protected final Metrics metrics = new Metrics();
        private final String mode;
        private final AtomicBoolean running = new AtomicBoolean();
        private volatile ServerSocket serverSocket;

        protected HttpServer(int port, String mode) {
            this.port = port;
            this.mode = mode;
        }

        public final void start() throws IOException {
            serverSocket = new ServerSocket(port);
            running.set(true);
            System.out.printf("Server dang lang nghe tren cong %d (%s).%n", port, mode);
            try {
                acceptLoop(serverSocket);
            } catch (SocketException exception) {
                if (running.get()) {
                    throw exception;
                }
            } finally {
                running.set(false);
                afterStop();
            }
        }

        protected abstract void acceptLoop(ServerSocket serverSocket) throws IOException;

        protected ClientHandler handler(Socket socket) {
            return new ClientHandler(socket, metrics, mode);
        }

        protected boolean isRunning() {
            return running.get();
        }

        public void stop() {
            if (!running.getAndSet(false)) {
                return;
            }
            try {
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (IOException exception) {
                System.err.println("Khong the dong server: " + exception.getMessage());
            }
        }

        protected void afterStop() {
            System.out.println("Server da dung.");
        }
    }

    /** Mode 1: handler.run() chay ngay tren thread main. */
    private static final class SingleThreadServer extends HttpServer {
        private SingleThreadServer(int port) {
            super(port, "single");
        }

        @Override
        protected void acceptLoop(ServerSocket serverSocket) throws IOException {
            while (isRunning()) {
                handler(serverSocket.accept()).run();
            }
        }
    }

    /** Mode 2: moi connection tao mot thread moi. */
    private static final class ThreadPerConnectionServer extends HttpServer {
        private final AtomicInteger threadNumber = new AtomicInteger();

        private ThreadPerConnectionServer(int port) {
            super(port, "thread-per-connection");
        }

        @Override
        protected void acceptLoop(ServerSocket serverSocket) throws IOException {
            while (isRunning()) {
                Socket socket = serverSocket.accept();
                new Thread(handler(socket),
                        "client-" + threadNumber.incrementAndGet()).start();
            }
        }
    }

    /** Mode 3: so worker co dinh, task duoc xep vao queue. */
    private static final class ThreadPoolServer extends HttpServer {
        private final ThreadPoolExecutor executor;

        private ThreadPoolServer(int port, int poolSize, int queueSize) {
            super(port, "thread-pool");
            AtomicInteger workerNumber = new AtomicInteger();
            executor = new ThreadPoolExecutor(
                    poolSize,
                    poolSize,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueSize),
                    task -> new Thread(task,
                            "pool-worker-" + workerNumber.incrementAndGet()),
                    new ThreadPoolExecutor.AbortPolicy());
        }

        @Override
        protected void acceptLoop(ServerSocket serverSocket) throws IOException {
            while (isRunning()) {
                Socket socket = serverSocket.accept();
                try {
                    executor.execute(handler(socket));
                } catch (RejectedExecutionException exception) {
                    reject(socket);
                }
            }
        }

        private void reject(Socket socket) {
            metrics.rejected();
            try (socket) {
                Response.text(503, "Service Unavailable",
                        "Server dang qua tai.").writeTo(socket.getOutputStream());
            } catch (IOException exception) {
                System.err.println("Khong the tra HTTP 503: " + exception.getMessage());
            }
        }

        @Override
        public void stop() {
            super.stop();
            executor.shutdown();
        }

        @Override
        protected void afterStop() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            super.afterStop();
        }
    }

    /** Doc mot request, chon endpoint va gui response. */
    private static final class ClientHandler implements Runnable {
        private final Socket socket;
        private final Metrics metrics;
        private final String mode;

        private ClientHandler(Socket socket, Metrics metrics, String mode) {
            this.socket = socket;
            this.metrics = metrics;
            this.mode = mode;
        }

        @Override
        public void run() {
            long started = System.nanoTime();
            boolean success = false;
            metrics.started();

            try (socket) {
                socket.setSoTimeout(10_000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                Response response;
                try {
                    response = route(Request.read(reader));
                } catch (IllegalArgumentException exception) {
                    response = Response.text(400, "Bad Request", exception.getMessage());
                }
                response.writeTo(socket.getOutputStream());
                success = response.status < 500;
            } catch (Exception exception) {
                System.err.printf("[%s] Loi: %s%n",
                        Thread.currentThread().getName(), exception.getMessage());
            } finally {
                long elapsed = System.nanoTime() - started;
                metrics.finished(elapsed, success);
                System.out.printf("[%s] client=%s elapsed=%.2fms success=%s%n",
                        Thread.currentThread().getName(), remoteAddress(),
                        elapsed / 1_000_000.0, success);
            }
        }

        private Response route(Request request) throws InterruptedException {
            if (!"GET".equals(request.method)) {
                return Response.text(405, "Method Not Allowed", "Chi ho tro GET.");
            }
            return switch (request.path) {
                case "/" -> dashboard();
                case "/health" -> Response.json(200, "OK", "{\"status\":\"UP\"}");
                case "/metrics" -> Response.json(200, "OK", metrics.toJson(mode));
                case "/sleep" -> slowResponse(request);
                default -> Response.text(404, "Not Found", "Khong tim thay duong dan.");
            };
        }

        private Response slowResponse(Request request) throws InterruptedException {
            int delay = request.queryInt("ms", 1_000, 0, 5_000);
            Thread.sleep(delay);
            String body = "{\"message\":\"completed\",\"delayMs\":" + delay
                    + ",\"thread\":\"" + Thread.currentThread().getName() + "\"}";
            return Response.json(200, "OK", body);
        }

        private Response dashboard() {
            String html = """
                    <!doctype html><html lang="vi"><head><meta charset="utf-8">
                    <title>T45 Java Server</title>
                    <style>body{font-family:system-ui;max-width:720px;margin:60px auto}
                    code{background:#eee;padding:4px}</style></head><body>
                    <h1>T45 - Java Multi-threaded HTTP Server</h1>
                    <p>Mode: <strong>%s</strong></p>
                    <p><a href="/sleep?ms=1000">/sleep?ms=1000</a></p>
                    <p><a href="/metrics">/metrics</a></p>
                    <p><code>%s</code></p></body></html>
                    """.formatted(mode, Instant.now());
            return Response.html(200, "OK", html);
        }

        private String remoteAddress() {
            return socket.getRemoteSocketAddress() == null
                    ? "unknown" : socket.getRemoteSocketAddress().toString();
        }
    }

    /** HTTP request toi thieu: method, path va query string. */
    private record Request(String method, String path, Map<String, String> query) {
        private static Request read(BufferedReader reader) throws IOException {
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isBlank()) {
                throw new IllegalArgumentException("Request line rong.");
            }
            String[] parts = requestLine.trim().split("\\s+");
            if (parts.length != 3 || !parts[2].startsWith("HTTP/")) {
                throw new IllegalArgumentException("Request line khong hop le.");
            }

            int headerCount = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (++headerCount > 100) {
                    throw new IllegalArgumentException("Qua nhieu HTTP header.");
                }
            }

            String target = parts[1];
            int questionMark = target.indexOf('?');
            String path = questionMark >= 0 ? target.substring(0, questionMark) : target;
            String rawQuery = questionMark >= 0 ? target.substring(questionMark + 1) : "";
            return new Request(parts[0], path, parseQuery(rawQuery));
        }

        private int queryInt(String name, int fallback, int min, int max) {
            String raw = query.get(name);
            if (raw == null) {
                return fallback;
            }
            try {
                int value = Integer.parseInt(raw);
                if (value < min || value > max) {
                    throw new NumberFormatException();
                }
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        name + " phai nam trong khoang " + min + ".." + max + ".");
            }
        }

        private static Map<String, String> parseQuery(String rawQuery) {
            Map<String, String> values = new HashMap<>();
            if (rawQuery.isBlank()) {
                return values;
            }
            for (String pair : rawQuery.split("&")) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String value = parts.length == 2
                        ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                values.put(key, value);
            }
            return values;
        }
    }

    /** Tao HTTP response va ghi ra socket. */
    private record Response(int status, String reason, String contentType, String body) {
        private static Response html(int status, String reason, String body) {
            return new Response(status, reason, "text/html; charset=UTF-8", body);
        }

        private static Response json(int status, String reason, String body) {
            return new Response(status, reason, "application/json; charset=UTF-8", body);
        }

        private static Response text(int status, String reason, String body) {
            return new Response(status, reason, "text/plain; charset=UTF-8", body);
        }

        private void writeTo(OutputStream output) throws IOException {
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(bodyBytes);
            output.flush();
        }
    }

    /** Bo dem thread-safe dung chung cho nhieu ClientHandler. */
    private static final class Metrics {
        private final LongAdder total = new LongAdder();
        private final LongAdder succeeded = new LongAdder();
        private final LongAdder failed = new LongAdder();
        private final LongAdder rejected = new LongAdder();
        private final LongAdder totalLatencyNanos = new LongAdder();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();

        private void started() {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
        }

        private void finished(long latencyNanos, boolean success) {
            total.increment();
            totalLatencyNanos.add(latencyNanos);
            if (success) {
                succeeded.increment();
            } else {
                failed.increment();
            }
            active.decrementAndGet();
        }

        private void rejected() {
            total.increment();
            failed.increment();
            rejected.increment();
        }

        private String toJson(String mode) {
            long count = total.sum();
            double averageMs = count == 0
                    ? 0 : totalLatencyNanos.sum() / 1_000_000.0 / count;
            return String.format(java.util.Locale.US,
                    "{\"mode\":\"%s\",\"total\":%d,\"succeeded\":%d,"
                            + "\"failed\":%d,\"rejected\":%d,\"active\":%d,"
                            + "\"maxActive\":%d,\"averageLatencyMs\":%.3f}",
                    mode, count, succeeded.sum(), failed.sum(), rejected.sum(),
                    active.get(), maxActive.get(), averageMs);
        }
    }
}

