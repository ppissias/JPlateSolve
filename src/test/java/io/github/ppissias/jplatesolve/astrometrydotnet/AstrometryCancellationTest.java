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
import java.io.ByteArrayOutputStream;
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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;
import static org.junit.Assert.*;

/** Exercises cloud operation contracts against an offline HTTP server with synthetic credentials. */
public class AstrometryCancellationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private HttpServer server;
    private ExecutorService handlers;
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String blockedPath;
    private volatile String jobStatus = "success";
    private volatile String loginAnswer = "{\"status\":\"success\",\"session\":\"test-session\"}";
    private volatile String uploadJson;
    private volatile String loginBody;
    /** Submission answers served before the job id; empty means the job id at once. */
    private final java.util.Queue<String> submissionStates = new java.util.concurrent.ConcurrentLinkedQueue<>();

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
                case "/api/login" -> {
                    loginBody = new String(input, StandardCharsets.UTF_8);
                    result = loginAnswer;
                }
                case "/api/upload" -> {
                    uploadJson = new String(input, StandardCharsets.UTF_8);
                    result = "{\"status\":\"success\",\"subid\":42}";
                }
                case "/api/submissions/42" -> {
                    String state = submissionStates.poll();
                    result = state != null ? state : "{\"jobs\":[7]}";
                }
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

    /**
     * A 4 x 4 16-bit FITS image whose primary header has {@code naxis1} as NAXIS1, written as it stands, and then
     * {@code cards}; written byte by byte so that values breaking the FITS rules reach the file.
     */
    private File fits(String naxis1, String... cards) throws Exception {
        StringBuilder header = new StringBuilder();
        java.util.List<String> all = new java.util.ArrayList<>(java.util.List.of("SIMPLE  =                    T",
                "BITPIX  =                   16", "NAXIS   =                    2",
                "NAXIS1  = " + String.format("%20s", naxis1), "NAXIS2  =                    4"));
        all.addAll(java.util.List.of(cards));
        all.add("END");
        for (String card : all) header.append(String.format("%-80s", card));
        while (header.length() % 2880 != 0) header.append(' ');
        File image = Files.createTempFile(folder.getRoot().toPath(), "image-", ".fit").toFile();
        byte[] bytes = header.toString().getBytes(StandardCharsets.US_ASCII);
        Files.write(image.toPath(), java.util.Arrays.copyOf(bytes, bytes.length + 2880));
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

    /** Blind uploads request private visibility without changing authentication. */
    @Test
    public void blindUploadsArePrivateByDefault() throws Exception {
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
            assertTrue(uploadJson.contains("\"publicly_visible\":\"n\""));
            assertTrue(uploadJson.contains("\"session\":\"test-session\""));
        }
    }

    /** Missing and blank visibility default to private only in the detached request. */
    @Test
    public void customUploadsDefaultToPrivateWithoutMutatingTheCaller() throws Exception {
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            for (String visibility : new String[]{null, "", " "}) {
                SubmitFileRequest request = SubmitFileRequest.builder().withPublicly_visible(visibility).build();
                var outcome = client.startCustomSolve(image(), request, null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
                assertTrue(uploadJson.contains("\"publicly_visible\":\"n\""));
                assertEquals(visibility, request.getPublicly_visible());
                assertNull(request.getSession());
            }
        }
    }

    /** An explicit public upload remains available without mutating the caller's parameters. */
    @Test
    public void customUploadsPreserveExplicitPublicVisibility() throws Exception {
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            SubmitFileRequest request = SubmitFileRequest.builder().withPublicly_visible("y").build();
            var outcome = client.startCustomSolve(image(), request, null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
            assertTrue(uploadJson.contains("\"publicly_visible\":\"y\""));
            assertEquals("y", request.getPublicly_visible());
            assertNull(request.getSession());
        }
    }

    /** Invalid null parameters retain their asynchronous operation failure without sending an upload. */
    @Test
    public void nullCustomParametersStillFailInsideTheOperation() throws Exception {
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            var operation = client.startCustomSolve(image(), null, null);
            var outcome = operation.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(PlateSolveOperation.Status.FAILED, outcome.status());
            assertTrue(outcome.cause() instanceof NullPointerException);
            assertEquals(0, requests.get());
        }
    }

    /** Fine-grained diagnostics contain no credentials during success, solver rejection or refused login. */
    @Test
    public void diagnosticLogsNeverContainAuthenticationCredentials() throws Exception {
        Logger logger = Logger.getLogger(AstrometryDotNet.class.getName());
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        Handler[] previousHandlers = logger.getHandlers();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        StreamHandler handler = new StreamHandler(captured, new SimpleFormatter());
        handler.setLevel(Level.ALL);
        for (Handler previous : previousHandlers) logger.removeHandler(previous);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.FINEST);
        try {
            try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
                var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(PlateSolveOperation.Status.SUCCEEDED, outcome.status());
                assertTrue(loginBody.contains("loopback-only"));
                assertTrue(uploadJson.contains("test-session"));
                jobStatus = "failure";
                outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(PlateSolveOperation.Status.REJECTED, outcome.status());
            }
            loginAnswer = "{\"status\":\"error\",\"errormessage\":\"unknown key loopback-only\"}";
            try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
                var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(PlateSolveOperation.Status.FAILED, outcome.status());
                logger.log(Level.FINE, "Login refused", outcome.cause());
            }
            handler.flush();
            String diagnostics = captured.toString(StandardCharsets.UTF_8);
            assertTrue("Diagnostic logging must be exercised", diagnostics.contains("Image solving"));
            assertTrue(diagnostics.contains("Login refused"));
            assertFalse(diagnostics.contains("loopback-only"));
            assertFalse(diagnostics.contains("test-session"));
        } finally {
            handler.close();
            logger.removeHandler(handler);
            for (Handler previous : previousHandlers) logger.addHandler(previous);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    @Test
    public void submissionPollWaitsThroughTheEmptyAndNullJobStates() throws Exception {
        // As nova.astrometry.net answered on 2026-10-21: no job yet, then a null job, then the job id.
        submissionStates.add("{\"user\": 1000, \"processing_started\": \"2026-10-21 12:39:47.506178+00:00\", "
                + "\"processing_finished\": \"None\", \"user_images\": [], \"images\": [], \"jobs\": [], \"job_calibrations\": []}");
        submissionStates.add("{\"user\": 1000, \"processing_started\": \"2026-10-21 12:39:47.506178+00:00\", "
                + "\"processing_finished\": \"2026-10-21 12:39:48.145917+00:00\", \"user_images\": [16574280], "
                + "\"images\": [41229374], \"jobs\": [null], \"job_calibrations\": []}");
        submissionStates.add("{\"user\": 1000, \"processing_started\": \"2026-10-21 12:39:47.506178+00:00\", "
                + "\"processing_finished\": \"2026-10-21 12:39:48.145917+00:00\", \"user_images\": [16574280], "
                + "\"images\": [41229374], \"jobs\": [7], \"job_calibrations\": [[7, 13648581]]}");
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
            assertTrue(outcome.result().getSolveInformation().get("wcs_link").endsWith("/wcs_file/7"));
            assertTrue("every state was polled", submissionStates.isEmpty());
        }
    }

    @Test
    public void aRefusedLoginCarriesTheServersReasonButNeverTheKey() throws Exception {
        // As nova.astrometry.net refuses an unknown key; the second answer echoes the key to prove it is redacted.
        loginAnswer = "{\"status\": \"error\", \"errormessage\": \"bad apikey\"}";
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            IOException refused = assertThrows(IOException.class, client::login);
            assertEquals("Astrometry.net login failed: bad apikey", refused.getMessage());
            var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(PlateSolveOperation.Status.FAILED, outcome.status());
            assertEquals("Astrometry.net login failed: bad apikey", outcome.cause().getMessage());
        }
        loginAnswer = "{\"status\": \"error\", \"errormessage\": \"unknown key loopback-only\"}";
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            IOException refused = assertThrows(IOException.class, client::login);
            assertFalse(refused.getMessage(), refused.getMessage().contains("loopback-only"));
        }
        loginAnswer = "{\"status\": \"error\"}";
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            assertEquals("Astrometry.net login failed.", assertThrows(IOException.class, client::login).getMessage());
        }
    }

    @Test
    public void anUnexpectedSubmissionAnswerFailsWithAReason() throws Exception {
        submissionStates.add("[\"not an object\"]");
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(PlateSolveOperation.Status.FAILED, outcome.status());
            assertNotNull("the failure says what went wrong", outcome.cause().getMessage());
        }
    }

    /** OBJCTRA and OBJCTDEC of a FITS header become the search centre of a request that has none. */
    @Test
    public void fitsObjectCoordinatesBecomeTheSearchCentre() throws Exception {
        File image = fits("4", "OBJCTRA = '12 30 00'", "OBJCTDEC= '+41 15 00'");
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            var outcome = client.startBlindSolve(image, null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
        }
        assertTrue(uploadJson, uploadJson.contains("\"center_ra\":187.5"));
        assertTrue(uploadJson, uploadJson.contains("\"center_dec\":41.25"));
    }

    /**
     * Header values that break the FITS number rules, or object coordinates that cannot be read, leave the request
     * as it is and the solve goes ahead; the header is read as text, so no number is parsed there.
     */
    @Test
    public void malformedHeaderValuesLeaveTheRequestUnchanged() throws Exception {
        File image = fits("4x", "BSCALE  =                1.0.0", "OBJCTRA = '12h30m'");
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            var outcome = client.startBlindSolve(image, null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
        }
        assertTrue(uploadJson, uploadJson.contains("\"center_ra\":0.0"));
    }

    /** A file named as FITS whose header cannot be read fails the solve with a cause, before anything is uploaded. */
    @Test
    public void anUnreadableFitsFileFailsBeforeUploading() throws Exception {
        File image = Files.createTempFile(folder.getRoot().toPath(), "image-", ".fit").toFile();
        Files.writeString(image.toPath(), String.format("%-80s", "SIMPLE  =                    T").substring(0, 40));
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            var outcome = client.startBlindSolve(image, null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(PlateSolveOperation.Status.FAILED, outcome.status());
            assertNotNull(outcome.cause());
        }
        assertNull("nothing is uploaded", uploadJson);
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
