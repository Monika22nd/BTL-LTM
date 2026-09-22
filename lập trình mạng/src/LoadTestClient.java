import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Tao nhieu HTTP request dong thoi va do latency/throughput. */
public final class LoadTestClient {
    private LoadTestClient() {
    }

    public static void main(String[] args) {
        try {
            Config config = Config.from(args);
            if (config.warmup > 0) {
                System.out.printf("Warm-up %d request...%n", config.warmup);
                execute(config.url, config.warmup,
                        Math.min(config.concurrency, config.warmup));
            }

            System.out.printf("Test %s: requests=%d, concurrency=%d%n",
                    config.url, config.requests, config.concurrency);
            Summary summary = execute(config.url, config.requests, config.concurrency);
            summary.print();

            if (config.csv != null) {
                writeCsv(config, summary);
                System.out.println("Da ghi ket qua: " + config.csv.toAbsolutePath());
            }
            if (summary.failed > 0) {
                System.exit(1);
            }
        } catch (IllegalArgumentException exception) {
            System.err.println("Loi tham so: " + exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Load test that bai: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static Summary execute(URI url, int requests, int concurrency) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        ExecutorService workers = Executors.newFixedThreadPool(concurrency);
        List<Callable<Sample>> tasks = new ArrayList<>(requests);
        for (int i = 0; i < requests; i++) {
            tasks.add(() -> sendOne(client, url));
        }

        long started = System.nanoTime();
        List<Future<Sample>> futures = workers.invokeAll(tasks);
        workers.shutdown();
        List<Sample> samples = new ArrayList<>(requests);
        for (Future<Sample> future : futures) {
            samples.add(future.get());
        }
        return Summary.from(samples, System.nanoTime() - started);
    }

    private static Sample sendOne(HttpClient client, URI url) {
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(url)
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Sample(response.statusCode() >= 200 && response.statusCode() < 400,
                    System.nanoTime() - started);
        } catch (Exception exception) {
            return new Sample(false, System.nanoTime() - started);
        }
    }

    private static void writeCsv(Config config, Summary summary) throws IOException {
        Path parent = config.csv.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean header = Files.notExists(config.csv) || Files.size(config.csv) == 0;
        StringBuilder text = new StringBuilder();
        if (header) {
            text.append("timestamp,url,requests,concurrency,success,failed,total_ms,"
                    + "throughput_rps,avg_ms,p50_ms,p95_ms,max_ms\n");
        }
        text.append(String.format(Locale.US,
                "%s,%s,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                Instant.now(), config.url, config.requests, config.concurrency,
                summary.success, summary.failed, summary.totalMs, summary.throughput,
                summary.averageMs, summary.p50Ms, summary.p95Ms, summary.maxMs));
        Files.writeString(config.csv, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private record Sample(boolean success, long latencyNanos) {
    }

    private record Summary(int success, int failed, double totalMs, double throughput,
                           double averageMs, double p50Ms, double p95Ms, double maxMs) {
        private static Summary from(List<Sample> samples, long totalNanos) {
            List<Long> latencies = samples.stream().map(Sample::latencyNanos)
                    .sorted(Comparator.naturalOrder()).toList();
            int success = (int) samples.stream().filter(Sample::success).count();
            int failed = samples.size() - success;
            double average = latencies.stream().mapToLong(Long::longValue)
                    .average().orElse(0) / 1_000_000.0;
            double totalMs = totalNanos / 1_000_000.0;
            double throughput = samples.size() / (totalNanos / 1_000_000_000.0);
            double p50 = percentile(latencies, 0.50) / 1_000_000.0;
            double p95 = percentile(latencies, 0.95) / 1_000_000.0;
            double max = latencies.isEmpty() ? 0
                    : latencies.get(latencies.size() - 1) / 1_000_000.0;
            return new Summary(success, failed, totalMs, throughput, average, p50, p95, max);
        }

        private static long percentile(List<Long> sorted, double value) {
            if (sorted.isEmpty()) {
                return 0;
            }
            int index = Math.max(0, (int) Math.ceil(value * sorted.size()) - 1);
            return sorted.get(index);
        }

        private void print() {
            System.out.println("---------------- KET QUA ----------------");
            System.out.printf("Thanh cong / that bai : %d / %d%n", success, failed);
            System.out.printf(Locale.US, "Tong thoi gian         : %.2f ms%n", totalMs);
            System.out.printf(Locale.US, "Throughput             : %.2f req/s%n", throughput);
            System.out.printf(Locale.US, "Latency trung binh     : %.2f ms%n", averageMs);
            System.out.printf(Locale.US, "Latency p50 / p95 / max: %.2f / %.2f / %.2f ms%n",
                    p50Ms, p95Ms, maxMs);
            System.out.println("-----------------------------------------");
        }
    }

    private record Config(URI url, int requests, int concurrency, int warmup, Path csv) {
        private static Config from(String[] args) {
            Map<String, String> options = new HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw new IllegalArgumentException("Tham so sai dinh dang: " + arg);
                }
                String[] pair = arg.substring(2).split("=", 2);
                options.put(pair[0], pair[1]);
            }
            URI url = URI.create(options.getOrDefault(
                    "url", "http://localhost:8080/sleep?ms=100"));
            int requests = number(options, "requests", 100, false);
            int concurrency = Math.min(
                    number(options, "concurrency", 10, false), requests);
            int warmup = number(options, "warmup", 10, true);
            Path csv = options.containsKey("csv") ? Path.of(options.get("csv")) : null;
            return new Config(url, requests, concurrency, warmup, csv);
        }

        private static int number(Map<String, String> values, String name,
                                  int fallback, boolean allowZero) {
            try {
                int value = Integer.parseInt(values.getOrDefault(name, String.valueOf(fallback)));
                if (value < (allowZero ? 0 : 1)) {
                    throw new NumberFormatException();
                }
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("--" + name + " khong hop le.");
            }
        }
    }
}

