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
    private volatile int httpStatusCode = 200;
    private volatile String errorPath;
    private volatile int uploadReadDelayMillis;
    private final java.util.concurrent.atomic.AtomicLong uploadedBytes = new java.util.concurrent.atomic.AtomicLong();
    private volatile String uploadResponse = "{\"status\":\"success\",\"subid\":42}";
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
            if (httpStatusCode >= 400 && path.equals(errorPath)) {
                byte[] error = "Server error".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(httpStatusCode, error.length);
                exchange.getResponseBody().write(error);
                return;
            }
            ByteArrayOutputStream prefix = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int uploadChunks = 0;
            int count;
            while ((count = exchange.getRequestBody().read(chunk)) != -1) {
                prefix.write(chunk, 0, Math.min(count, Math.max(0, 2048 - prefix.size())));
                if (path.equals("/api/upload")) {
                    uploadedBytes.addAndGet(count);
                    if (uploadReadDelayMillis > 0 && ++uploadChunks % 16 == 0) {
                        try { Thread.sleep(uploadReadDelayMillis); }
                        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
                    }
                }
            }
            byte[] input = prefix.toByteArray();
            String result;
            switch (path) {
                case "/api/login" -> {
                    loginBody = new String(input, StandardCharsets.UTF_8);
                    result = loginAnswer;
                }
                case "/api/upload" -> {
                    uploadJson = new String(input, StandardCharsets.UTF_8);
                    result = uploadResponse;
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
        return new AstrometryDotNet(configuration(budget, executor).build());
    }

    /** Builds isolated client limits for loopback tests; callers can override a specific phase. */
    private AstrometryDotNetConfig.Builder configuration(Duration budget, ExecutorService executor) {
        return AstrometryDotNetConfig.builder()
                .withApiKey("loopback-only")
                .withServiceBaseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                .withSolveTimeout(budget).withUploadTimeout(Duration.ofSeconds(30)).withStatusTimeout(Duration.ofSeconds(30))
                .withPollInterval(Duration.ofMillis(20)).withExecutorService(executor);
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
            var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
            assertEquals("12.5", outcome.result().getSolveInformation().get("ra"));
            assertEquals("-7.25", outcome.result().getSolveInformation().get("dec"));
            assertTrue(uploadJson.contains("\"publicly_visible\":\"n\""));
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
            assertBlindParameters();
        }
    }

    /** An immutable configuration can deliberately opt into public blind uploads. */
    @Test
    public void publicBlindUploadsRequireExplicitConfiguration() throws Exception {
        AstrometryDotNetConfig.Builder configuration = configuration(Duration.ofSeconds(5), null).withPubliclyVisible(true);
        try (AstrometryDotNet client = new AstrometryDotNet(configuration.build())) {
            configuration.withPubliclyVisible(false);
            var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
            assertTrue(uploadJson.contains("\"publicly_visible\":\"y\""));
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

    /** Valid, partial and malformed pointing cards never become numeric upload constraints. */
    @Test
    public void blindFitsUploadsIgnoreEveryPointingHint() throws Exception {
        for (String[] cards : new String[][]{
                {"OBJCTRA = '12 30 00'", "OBJCTDEC= '+41 15 00'"},
                {"OBJCTRA = '12 30 00'"},
                {"OBJCTRA = 'NaN 00 00'", "OBJCTDEC= '-00 30 00'"},
                {"RA      =                187.5", "DEC     =                41.25", "FOCALLEN=                500.0"},
                {}}) {
            File image = fits("4", cards);
            try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
                var outcome = client.startBlindSolve(image, null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
                assertBlindParameters();
            }
        }
    }

    /** Structurally readable headers do not require valid numbers in unrelated cards. */
    @Test
    public void malformedHintValuesDoNotPreventABlindSolve() throws Exception {
        File image = fits("4x", "BSCALE  =                1.0.0", "OBJCTRA = '12h30m'");
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            var outcome = client.startBlindSolve(image, null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
            assertBlindParameters();
        }
    }

    /** Caller hints reach the actual upload with degree coordinates and an arcsec/pixel interval. */
    @Test public void hintedSolveSendsPositionAndScale() throws Exception {
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            var hints = new io.github.ppissias.jplatesolve.SolveHints(120, -20, 0.5, 1.2, 0.3);
            var outcome = client.startSolve(image(), hints, null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
            int begin = uploadJson.indexOf('{');
            int end = uploadJson.indexOf('}', begin) + 1;
            var json = com.google.gson.JsonParser.parseString(uploadJson.substring(begin, end)).getAsJsonObject();
            assertEquals(120, json.get("center_ra").getAsDouble(), 0);
            assertEquals(-20, json.get("center_dec").getAsDouble(), 0);
            assertEquals(0.5, json.get("radius").getAsDouble(), 0);
            assertEquals("arcsecperpix", json.get("scale_units").getAsString());
            assertEquals(0.6, json.get("scale_lower").getAsDouble(), 0);
            assertEquals(2.4, json.get("scale_upper").getAsDouble(), 0);
            assertEquals("n", json.get("publicly_visible").getAsString());
        }
    }

    /** Checks the actual multipart JSON rather than the DTO serializer in isolation. */
    private void assertBlindParameters() {
        int begin = uploadJson.indexOf('{');
        int end = uploadJson.indexOf('}', begin) + 1;
        var parameters = com.google.gson.JsonParser.parseString(uploadJson.substring(begin, end)).getAsJsonObject();
        assertEquals(java.util.Set.of("session", "publicly_visible"), parameters.keySet());
        assertEquals("test-session", parameters.get("session").getAsString());
        assertEquals("n", parameters.get("publicly_visible").getAsString());
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
            assertTrue("cause is FitsException", outcome.cause() instanceof nom.tam.fits.FitsException);
            assertTrue("cause names file: " + outcome.cause().getMessage(), outcome.cause().getMessage().contains(image.getName()));
            assertTrue("cause explains unreadable: " + outcome.cause().getMessage(), outcome.cause().getMessage().contains("not a readable FITS image"));
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

    @Test
    public void multipleJobsSelectsFirstNonNullJob() throws Exception {
        submissionStates.add("{\"jobs\":[null, 7, 8]}");
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(PlateSolveOperation.Status.SUCCEEDED, outcome.status());
            assertNotNull(outcome.result());
            assertTrue(outcome.result().isSuccess());
            assertNull(outcome.result().getFailureReason());
            assertNull(outcome.result().getWarning());
            assertEquals("12.5", outcome.result().getSolveInformation().get("ra"));
            assertEquals("-7.25", outcome.result().getSolveInformation().get("dec"));
        }
    }

    @Test
    public void malformedJsonInSubmissionFailsOperation() throws Exception {
        submissionStates.add("{not-valid-json");
        try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
            var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(PlateSolveOperation.Status.FAILED, outcome.status());
            assertNotNull(outcome.cause());
            assertTrue(outcome.cause() instanceof IOException);
            assertTrue(outcome.cause().getMessage().contains("unreadable submission status"));
        }
    }

    /** An accepted upload without an identifier is a protocol failure, and never starts polling. */
    @Test
    public void absentOrBlankSubmissionIdsFailBeforePolling() throws Exception {
        for (String response : new String[]{"{\"status\":\"success\"}",
                "{\"status\":\"success\",\"subid\":null}",
                "{\"status\":\"success\",\"subid\":\"\"}",
                "{\"status\":\"success\",\"subid\":\"  \"}"}) {
            uploadResponse = response;
            int before = requests.get();
            try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
                var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(PlateSolveOperation.Status.FAILED, outcome.status());
                assertNull(outcome.result());
                assertTrue(outcome.cause() instanceof IOException);
                assertTrue(outcome.cause().getMessage().contains("without a submission identifier"));
                assertEquals("Only login and upload are sent", 2, requests.get() - before);
            }
        }
    }

    @Test
    public void http500ErrorFailsOperation() throws Exception {
        httpStatusCode = 500;
        for (String path : new String[]{"/api/login", "/api/upload", "/api/submissions/42", "/api/jobs/7/info"}) {
            errorPath = path;
            try (AstrometryDotNet client = client(Duration.ofSeconds(5), null)) {
                var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(path, PlateSolveOperation.Status.FAILED, outcome.status());
                assertNotNull(outcome.cause());
                assertTrue(outcome.cause().getMessage().contains("HTTP status 500"));
            }
        }
    }

    /** A throttled body larger than socket buffers can outlast polling limits within its own upload limit. */
    @Test
    public void slowUploadUsesItsOwnBudgetAndBoundedServerBuffers() throws Exception {
        File file = image();
        try (RandomAccessFile data = new RandomAccessFile(file, "rw")) { data.setLength(16L * 1024 * 1024); }
        uploadReadDelayMillis = 10;
        try (AstrometryDotNet client = new AstrometryDotNet(configuration(Duration.ofSeconds(15), null)
                .withUploadTimeout(Duration.ofSeconds(10)).withStatusTimeout(Duration.ofMillis(500)).build())) {
            long start = System.nanoTime();
            var outcome = client.startBlindSolve(file, null).completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
            assertTrue(uploadedBytes.get() > 16L * 1024 * 1024);
            assertTrue(System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(500));
            assertBlindParameters();
        }
    }

    /** Independent phase failures return promptly instead of consuming the total solve budget. */
    @Test
    public void everyRouteTimeoutRemainsAnIndependentFailure() throws Exception {
        for (String path : new String[]{"/api/login", "/api/upload", "/api/submissions/42", "/api/jobs/7/info"}) {
            blockedPath = path;
            try (AstrometryDotNet client = new AstrometryDotNet(configuration(Duration.ofSeconds(6), null)
                    .withUploadTimeout(Duration.ofMillis(250)).withStatusTimeout(Duration.ofMillis(250)).build())) {
                long start = System.nanoTime();
                var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(path, PlateSolveOperation.Status.FAILED, outcome.status());
                assertTrue(outcome.cause() instanceof java.net.http.HttpTimeoutException);
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3));
            }
        }
    }

    /** The total monotonic budget still caps all phases even when their individual limits are longer. */
    @Test
    public void everyRouteIsCappedByTheTotalBudget() throws Exception {
        for (String path : new String[]{"/api/login", "/api/upload", "/api/submissions/42", "/api/jobs/7/info"}) {
            blockedPath = path;
            try (AstrometryDotNet client = client(Duration.ofMillis(500), null)) {
                var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(path, PlateSolveOperation.Status.TIMED_OUT, outcome.status());
            }
        }
    }

    /** A new connection's stalled TLS handshake must not wait out the request or solve budget. */
    @Test
    public void connectionTimeoutRemainsIndependentEvenWhenRequestIsDeadlineLimited() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            java.util.concurrent.Future<?> peer = handlers.submit(() -> {
                try (java.net.Socket accepted = socket.accept()) { release.await(); }
                catch (IOException | InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            try (AstrometryDotNet client = new AstrometryDotNet(configuration(Duration.ofSeconds(5), null)
                    .withServiceBaseUri(URI.create("https://localhost:" + socket.getLocalPort() + "/"))
                    .withConnectTimeout(Duration.ofMillis(250)).build())) {
                long start = System.nanoTime();
                var outcome = client.startBlindSolve(image(), null).completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(PlateSolveOperation.Status.FAILED, outcome.status());
                assertTrue(String.valueOf(outcome.cause()), outcome.cause() instanceof java.net.http.HttpConnectTimeoutException);
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3));
            } finally {
                release.countDown();
                peer.get(3, TimeUnit.SECONDS);
            }
        }
    }
}
