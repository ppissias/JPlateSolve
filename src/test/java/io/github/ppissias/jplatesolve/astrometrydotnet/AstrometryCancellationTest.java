package io.github.ppissias.jplatesolve.astrometrydotnet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ppissias.jplatesolve.PlateSolveOperation;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AstrometryCancellationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private HttpServer server;
    private ExecutorService handlers;
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String blockedPath;
    private volatile String jobStatus = "success";
    private volatile String uploadJson;

    @Before
    public void setup() throws Exception {
        handlers = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.createContext("/", this::handle);
        server.start();
    }

    @After
    public void cleanup() throws Exception {
        release.countDown();
        server.stop(0);
        handlers.shutdownNow();
        assertTrue(handlers.awaitTermination(5, TimeUnit.SECONDS));
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.equals(blockedPath)) {
                reached.countDown();
                try { release.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
            }
            byte[] input = exchange.getRequestBody().readAllBytes();
            String result;
            switch (path) {
                case "/api/login" -> result = "{\"status\":\"success\",\"session\":\"test-session\"}";
                case "/api/upload" -> {
                    uploadJson = new String(input, StandardCharsets.UTF_8);
                    result = "{\"status\":\"success\",\"subid\":42}";
                }
                case "/api/submissions/42" -> result = "{\"jobs\":[7]}";
                case "/api/jobs/7/info" -> result = "{\"status\":\"" + jobStatus + "\",\"calibration\":{\"ra\":12.5,\"dec\":-7.25}}";
                default -> result = "{}";
            }
            byte[] body = result.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private AstrometryDotNet client(Duration budget, ExecutorService executor) {
        return new AstrometryDotNet(AstrometryDotNetConfig.builder()
                .withApiKey("loopback-only")
                .withServiceBaseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                .withSolveTimeout(budget).withRequestTimeout(Duration.ofSeconds(30))
                .withPollInterval(Duration.ofMillis(20)).withExecutorService(executor).build());
    }

    private File image() throws Exception {
        File image = Files.createTempFile(folder.getRoot().toPath(), "image-", ".jpg").toFile();
        Files.writeString(image.toPath(), "test-image");
        return image;
    }

    private void cancelAt(String path, File image) throws Exception {
        blockedPath = path;
        try (AstrometryDotNet client = client(Duration.ofSeconds(10), null)) {
            long start = System.nanoTime();
            PlateSolveOperation operation = client.startBlindSolve(image, null);
            assertTrue("Entry point must return before blocked HTTP finishes", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            assertTrue(reached.await(5, TimeUnit.SECONDS));
            long cancelled = System.nanoTime();
            assertTrue(operation.cancel());
            var outcome = operation.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.CANCELLED, outcome.status());
            double elapsedMillis = (System.nanoTime() - cancelled) / 1_000_000.0;
            int after = requests.get();
            Thread.sleep(100);
            assertEquals(after, requests.get());
            System.out.println(path + " Cancel to local completion ms: " + elapsedMillis);
        }
    }

    @Test public void cancelDuringLogin() throws Exception { cancelAt("/api/login", image()); }

    @Test
    public void cancelDuringStreamingUpload() throws Exception {
        File image = image();
        try (RandomAccessFile file = new RandomAccessFile(image, "rw")) { file.setLength(32L * 1024 * 1024); }
        cancelAt("/api/upload", image);
    }

    @Test public void cancelDuringPolling() throws Exception { cancelAt("/api/jobs/7/info", image()); }

    @Test
    public void totalBudgetIncludesLoginAndCapsItsRequest() throws Exception {
        blockedPath = "/api/login";
        try (AstrometryDotNet client = client(Duration.ofMillis(300), null)) {
            long started = System.nanoTime();
            var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.TIMED_OUT, outcome.status());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3));
        }
    }

    @Test
    public void successPreservesCalibrationAndPrivateUploadParameters() throws Exception {
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            SubmitFileRequest request = SubmitFileRequest.builder().withPublicly_visible("n").build();
            var outcome = client.startCustomSolve(image(), request, null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
            assertEquals("12.5", outcome.result().getSolveInformation().get("ra"));
            assertEquals("-7.25", outcome.result().getSolveInformation().get("dec"));
            assertTrue(uploadJson.contains("\"publicly_visible\":\"n\""));
            assertNull("The caller's request is not mutated", request.getSession());
            assertTrue(outcome.result().getSolveInformation().get("wcs_link").endsWith("/wcs_file/7"));
        }
    }

    @Test
    public void remoteRejectionRemainsDistinctFromTransportFailure() throws Exception {
        jobStatus = "failure";
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            assertEquals(PlateSolveOperation.Status.REJECTED,
                    client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        }
    }

    @Test
    public void closeCancelsItsWorkButDoesNotShutDownBorrowedExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AstrometryDotNet client = client(Duration.ofSeconds(10), executor);
            blockedPath = "/api/login";
            PlateSolveOperation operation = client.startBlindSolve(image(), null);
            assertTrue(reached.await(5, TimeUnit.SECONDS));
            client.close();
            assertTrue(operation.isDone());
            assertEquals(PlateSolveOperation.Status.CANCELLED, operation.completion().toCompletableFuture().get().status());
            assertFalse(executor.isShutdown());
            assertEquals(42, (int) executor.submit(() -> 42).get(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> client.startBlindSolve(new File("unused"), null));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void queuedSolveCanCancelBeforeAnyHttpRequest() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch busy = new CountDownLatch(1);
        executor.submit(() -> { try { busy.await(); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); } });
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), executor)) {
            PlateSolveOperation operation = client.startBlindSolve(image(), null);
            assertTrue(operation.cancel());
            assertEquals(PlateSolveOperation.Status.CANCELLED, operation.completion().toCompletableFuture().get().status());
            assertEquals(0, requests.get());
        } finally {
            busy.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void closingOneClientJoinsItsOwnedWorkerWithoutAffectingAnother() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Thread> worker = new java.util.concurrent.atomic.AtomicReference<>();
        try (AstrometryDotNet first = client(Duration.ofSeconds(5), null);
             AstrometryDotNet second = client(Duration.ofSeconds(5), null)) {
            var operation = first.startBlindSolve(image(), progress -> worker.set(Thread.currentThread()));
            assertEquals(PlateSolveOperation.Status.SUCCEEDED, operation.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
            first.close();
            first.close();
            worker.get().join(1000);
            assertFalse(worker.get().isAlive());
            assertEquals(PlateSolveOperation.Status.SUCCEEDED,
                    second.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        }
    }

    @Test
    public void closeDuringUploadCancelsAndJoinsTheOperation() throws Exception {
        blockedPath = "/api/upload";
        File image = image();
        try (RandomAccessFile file = new RandomAccessFile(image, "rw")) { file.setLength(32L * 1024 * 1024); }
        try (AstrometryDotNet client = client(Duration.ofSeconds(10), null)) {
            PlateSolveOperation operation = client.startBlindSolve(image, null);
            assertTrue(reached.await(5, TimeUnit.SECONDS));
            client.close();
            assertTrue(operation.isDone());
            assertEquals(PlateSolveOperation.Status.CANCELLED, operation.completion().toCompletableFuture().get().status());
        }
    }
}
