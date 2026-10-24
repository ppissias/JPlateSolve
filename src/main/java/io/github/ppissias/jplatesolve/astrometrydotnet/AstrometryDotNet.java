/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jplatesolve.astrometrydotnet;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.PlateSolveOperation;
import io.github.ppissias.jplatesolve.PlateSolveOperation.Context;
import io.github.ppissias.jplatesolve.PlateSolveOperation.Phase;
import io.github.ppissias.jplatesolve.astrometrydotnet.util.SubmitFileBodyPublisher;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.util.Cursor;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Client for the Astrometry.net HTTP API.
 * <p>
 * The client logs in lazily, uploads images for solving, polls the remote job
 * until completion, and converts the remote response into a
 * {@link PlateSolveResult}. Unless configured otherwise, it uses the built-in
 * guest API key and an internally managed executor service.
 */
public class AstrometryDotNet implements AutoCloseable {
    private static final AtomicInteger WORKERS = new AtomicInteger();

    //astrometry.net Service URIs
    private static final String LOGIN_URI = "api/login";
    private static final String SUBMIT_FILE_URI = "api/upload";
    private static final String SUBMISSION_PROGRESS_URI = "api/submissions/";//+SUBID
    private static final String JOB_PROGRESS_URI = "api/jobs/"; //+JOBID/info
    private static final String WCS_BASE_URI = "wcs_file/"; //+JOBID
    //links with info
    private static final String ANNOTATED_IMAGE_LINK = "annotated_display/"; //+JOBID
    private static final String RESULTS_PAGE_LINK = "status/"; //+StatusID

    /**
     * Default guest API key used when no explicit API key is configured.
     */
    public static final String DEFAULT_API_KEY = "XXXXXXXX";
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_SOLVE_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);

    //Astrometry.net session ID, after logging
    private volatile String sessionID = null;

    //Gson object used for JSON transformations to java objects back and forth
    private final Gson gson;

    //logger
    private final Logger logger;

    //HTTP client used to make calls to Astrometry.net
    private final HttpClient client;

    //Executor service
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final String apiKey;
    private final Duration requestTimeout;
    private final Duration solveTimeout;
    private final Duration pollInterval;
    private final URI serviceBaseUri;
    private final Object lifecycle = new Object();
    private final Set<PlateSolveOperation> operations = new HashSet<>();
    private final ReentrantLock loginLock = new ReentrantLock();
    private volatile boolean closed;

    /**
     * Constructs a new client using the default guest Astrometry.net API key and
     * internal execution resources.
     */
    public AstrometryDotNet() {
        this(AstrometryDotNetConfig.builder().build());
    }

    /**
     * Constructs a new client using a caller-provided API key and otherwise
     * default settings.
     *
     * @param apiKey the Astrometry.net API key; blank values fall back to the guest key
     */
    public AstrometryDotNet(String apiKey) {
        this(AstrometryDotNetConfig.builder().withApiKey(apiKey).build());
    }

    /**
     * Constructs a new client using the provided configuration.
     *
     * @param config runtime settings for API key, timeouts, polling, and execution
     */
    public AstrometryDotNet(AstrometryDotNetConfig config) {
        AstrometryDotNetConfig effectiveConfig = config == null ? AstrometryDotNetConfig.builder().build() : config;
        this.gson = new Gson();
        this.logger = Logger.getLogger(AstrometryDotNet.class.getName());
        this.apiKey = sanitizeApiKey(effectiveConfig.getApiKey());
        this.requestTimeout = sanitizeDuration(effectiveConfig.getRequestTimeout(), DEFAULT_REQUEST_TIMEOUT);
        this.solveTimeout = sanitizeDuration(effectiveConfig.getSolveTimeout(), DEFAULT_SOLVE_TIMEOUT);
        this.pollInterval = sanitizeDuration(effectiveConfig.getPollInterval(), DEFAULT_POLL_INTERVAL);

        URI root = effectiveConfig.getServiceBaseUri() == null
                ? URI.create("https://nova.astrometry.net/") : effectiveConfig.getServiceBaseUri();
        if (!root.isAbsolute() || !("http".equals(root.getScheme()) || "https".equals(root.getScheme()))
                || root.getHost() == null || root.getQuery() != null || root.getFragment() != null) {
            throw new IllegalArgumentException("Service base URI must be an absolute HTTP(S) root without query or fragment.");
        }
        this.serviceBaseUri = URI.create(root.toString().endsWith("/") ? root.toString() : root + "/");

        ExecutorService configuredExecutor = effectiveConfig.getExecutorService();
        if (configuredExecutor == null) {
            this.executor = Executors.newFixedThreadPool(3, action -> {
                Thread thread = Executors.defaultThreadFactory().newThread(action);
                thread.setName("jplatesolve-astrometry-" + WORKERS.incrementAndGet());
                return thread;
            });
            this.ownsExecutor = true;
        } else {
            this.executor = configuredExecutor;
            this.ownsExecutor = false;
        }

        this.client = HttpClient.newBuilder()
                .version(Version.HTTP_2)
                .connectTimeout(this.requestTimeout)
                .build();
    }

    /**
     * Returns the session ID of this intance.
     * @return the active Astrometry.net session identifier, or null if not logged in
     */
    public String getSessionID() {
        return this.sessionID;
    }

    /**
     * Returns the API key currently configured for this client.
     *
     * @return configured API key, or the default guest key when no explicit key
     *         was supplied
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Returns the timeout applied to individual HTTP requests.
     *
     * @return per-request timeout
     */
    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    /**
     * Returns the total timeout for a remote solve operation.
     *
     * @return overall solve timeout
     */
    public Duration getSolveTimeout() {
        return solveTimeout;
    }

    /**
     * Returns the poll interval used while waiting for submission and job status.
     *
     * @return polling interval between Astrometry.net status checks
     */
    public Duration getPollInterval() {
        return pollInterval;
    }

    /** Logs in synchronously. New solves perform login inside their cancellable operation. */
    public void login() throws IOException, InterruptedException {
        loginLock.lockInterruptibly();
        try {
            if (closed) throw new IOException("Astrometry.net client is closed.");
            login(null);
        } finally {
            loginLock.unlock();
        }
    }

    private void login(Context context) throws IOException, InterruptedException {
        LoginRequest parameters = LoginRequest.builder().withApikey(apiKey).build();
        String body = "request-json=" + URLEncoder.encode(gson.toJson(parameters), StandardCharsets.UTF_8);
        HttpRequest request = requestBuilder(LOGIN_URI, context)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/x-www-form-urlencoded").build();
        HttpResponse<String> response = sendRequest(request, context);
        LoginResponse result = gson.fromJson(response.body(), LoginResponse.class);
        if (result == null || !"success".equals(result.getStatus())
                || result.getSession() == null || result.getSession().isBlank()) {
            // The server's reason (nova answers "bad apikey" for an unknown key), never the key itself.
            String reason = result == null ? null : result.getErrormessage();
            if (reason == null || reason.isBlank()) throw new IOException("Astrometry.net login failed.");
            throw new IOException("Astrometry.net login failed: " + reason.replace(apiKey, "<api key>"));
        }
        sessionID = result.getSession();
    }

    /** Legacy adapter. Login and FITS preparation now execute asynchronously as part of the solve. */
    public Future<PlateSolveResult> blindSolve(File imageFile) throws IOException, InterruptedException {
        return startBlindSolve(imageFile, null).asFuture(true);
    }

    public Future<PlateSolveResult> customSolve(File imageFile, SubmitFileRequest parameters)
            throws IOException, InterruptedException, FitsException {
        return startCustomSolve(imageFile, parameters, null).asFuture(true);
    }

    /** Starts a blind solve with a private upload; use a custom request to opt into public visibility. */
    public PlateSolveOperation startBlindSolve(File imageFile, Consumer<PlateSolveOperation.Progress> progress) {
        return startCustomSolve(imageFile, SubmitFileRequest.builder().withPublicly_visible("n").build(), progress);
    }

    /**
     * Starts a solve with detached parameters, defaulting absent or blank visibility to a private upload.
     * Cancellation stops local requests and polling, not a remote job already accepted by Astrometry.net.
     */
    public PlateSolveOperation startCustomSolve(File imageFile, SubmitFileRequest parameters,
                                                Consumer<PlateSolveOperation.Progress> progress) {
        SubmitFileRequest snapshot = gson.fromJson(gson.toJson(parameters), SubmitFileRequest.class);
        if (snapshot != null && (snapshot.getPublicly_visible() == null || snapshot.getPublicly_visible().isBlank())) {
            snapshot.setPublicly_visible("n");
        }
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("Astrometry.net client is closed.");
            PlateSolveOperation operation = PlateSolveOperation.start(solveTimeout, progress, executor, context -> {
                context.report(Phase.PREPARING, "Preparing Astrometry.net solve");
                if (snapshot.getSession() == null || snapshot.getSession().isBlank()) {
                    context.report(Phase.LOGGING_IN, "Logging in to Astrometry.net");
                    loginLock.lockInterruptibly();
                    try {
                        context.checkCancelled();
                        if (sessionID == null || sessionID.isBlank()) login(context);
                        snapshot.setSession(sessionID);
                    } finally {
                        loginLock.unlock();
                    }
                }
                context.checkCancelled();
                if (isFitsFile(imageFile)) updateFromFitsHeader(imageFile, snapshot);
                context.checkCancelled();
                return performSolve(imageFile, snapshot, context);
            });
            operations.add(operation);
            operation.completion().whenComplete((outcome, failure) -> {
                synchronized (lifecycle) { operations.remove(operation); }
            });
            return operation;
        }
    }

    /** Cancels and joins this client's solves, closes HTTP resources, and never shuts down a borrowed executor. */
    @Override
    public void close() {
        ArrayList<PlateSolveOperation> pending;
        synchronized (lifecycle) {
            closed = true;
            pending = new ArrayList<>(operations);
        }
        pending.forEach(PlateSolveOperation::cancel);
        client.shutdownNow();
        if (ownsExecutor) executor.shutdownNow();
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            for (PlateSolveOperation operation : pending) {
                while (!operation.isDone()) {
                    try {
                        operation.completion().toCompletableFuture().get(remainingCloseNanos(deadline), TimeUnit.NANOSECONDS);
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    } catch (java.util.concurrent.ExecutionException impossible) {
                        throw new IllegalStateException(impossible);
                    }
                }
            }
            while (true) {
                try {
                    if (ownsExecutor && !executor.awaitTermination(remainingCloseNanos(deadline), TimeUnit.NANOSECONDS)) {
                        throw new TimeoutException("Solve executor did not terminate.");
                    }
                    if (!client.awaitTermination(Duration.ofNanos(remainingCloseNanos(deadline)))) {
                        throw new TimeoutException("HTTP client did not terminate.");
                    }
                    break;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("Cannot confirm Astrometry.net client shutdown.", timeout);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static long remainingCloseNanos(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("Client shutdown deadline expired.");
        return remaining;
    }

    private PlateSolveResult performSolve(File imageFile, SubmitFileRequest parameters, Context context)
            throws IOException, InterruptedException {
        context.report(Phase.UPLOADING, "Uploading image to Astrometry.net");
        String boundary = UUID.randomUUID().toString();
        HttpRequest request = requestBuilder(SUBMIT_FILE_URI, context)
                .POST(SubmitFileBodyPublisher.getBodyPublisher(imageFile, gson.toJson(parameters), boundary))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).build();
        HttpResponse<String> response = sendRequest(request, context);
        SubmitFileResponse submission = gson.fromJson(response.body(), SubmitFileResponse.class);
        if (submission == null || !"success".equals(submission.getStatus())) {
            return failureResult("Submit request was not successful", response.body());
        }
        context.report(Phase.WAITING_FOR_JOB, "Waiting for Astrometry.net job");
        int jobID = waitForJobId(submission, context);
        context.report(Phase.SOLVING, "Astrometry.net is solving");
        JobResultResponse result = waitForJobResult(jobID, context);
        context.report(Phase.READING_RESULT, "Reading Astrometry.net result");
        return mapSolveResult(submission, jobID, result);
    }

    private int waitForJobId(SubmitFileResponse submission, Context context) throws IOException, InterruptedException {
        while (true) {
            HttpResponse<String> response = sendRequest(requestBuilder(SUBMISSION_PROGRESS_URI + submission.getSubid(), context)
                    .GET().build(), context);
            Integer jobID = firstJobId(response.body());
            if (jobID != null) return jobID;
            context.pause(pollInterval);
        }
    }

    /**
     * The submission's first job id, or null while it has none. After an upload Astrometry.net answers
     * {@code "jobs": []}, then {@code "jobs": [null]}, then the id. Only {@code jobs} is read: the
     * other id arrays can hold nulls too, which a primitive {@code int[]} cannot.
     */
    static Integer firstJobId(String body) throws IOException {
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (JsonParseException malformed) {
            throw new IOException("Astrometry.net returned an unreadable submission status.", malformed);
        }
        if (!root.isJsonObject()) {
            throw new IOException("Astrometry.net returned an unexpected submission status.");
        }
        JsonElement jobs = root.getAsJsonObject().get("jobs");
        if (jobs == null || !jobs.isJsonArray()) {
            return null;
        }
        for (JsonElement job : jobs.getAsJsonArray()) {
            if (!job.isJsonNull()) {
                return job.getAsInt();
            }
        }
        return null;
    }

    private JobResultResponse waitForJobResult(int jobID, Context context) throws IOException, InterruptedException {
        while (true) {
            HttpResponse<String> response = sendRequest(requestBuilder(JOB_PROGRESS_URI + jobID + "/info", context)
                    .GET().build(), context);
            JobResultResponse result = gson.fromJson(response.body(), JobResultResponse.class);
            if (result != null && ("success".equals(result.getStatus()) || "failure".equals(result.getStatus()))) return result;
            context.pause(pollInterval);
        }
    }

    private PlateSolveResult mapSolveResult(SubmitFileResponse submitFileResponse, int jobID, JobResultResponse jobResResponse) {
        if (jobResResponse != null && "success".equals(jobResResponse.getStatus())) {
            logger.fine("Image solving was sucecesful :" + jobResResponse);

            Map<String, String> solveInformation = new HashMap<>();
            solveInformation.put("source", "astrometry.net");
            solveInformation.put("original_response", gson.toJson(jobResResponse));
            solveInformation.put("annotated_image_link", serviceBaseUri.resolve(ANNOTATED_IMAGE_LINK + jobID).toString());
            solveInformation.put("status_page_link", serviceBaseUri.resolve(RESULTS_PAGE_LINK + submitFileResponse.getSubid()).toString());
            solveInformation.put("wcs_link", serviceBaseUri.resolve(WCS_BASE_URI + jobID).toString());

            if (jobResResponse.getCalibration() != null) {
                solveInformation.put("dec", "" + jobResResponse.getCalibration().getDec());
                solveInformation.put("ra", "" + jobResResponse.getCalibration().getRa());
                solveInformation.put("orientation", "" + jobResResponse.getCalibration().getOrientation());
                solveInformation.put("pixscale", "" + jobResResponse.getCalibration().getPixscale());
                solveInformation.put("radius", "" + jobResResponse.getCalibration().getRadius());
                solveInformation.put("parity", "" + jobResResponse.getCalibration().getParity());
            }

            PlateSolveResult ret = new PlateSolveResult(true, "", "", solveInformation);
            logger.fine("Will return to the user:" + ret);
            return ret;
        }

        logger.fine("Image solving was not sucecesful :" + jobResResponse);
        return failureResult(jobResResponse == null ? "Astrometry.net returned no job result" : jobResResponse.toString(),
                jobResResponse == null ? null : gson.toJson(jobResResponse));
    }

    private PlateSolveResult failureResult(String failureReason, String originalResponse) {
        Map<String, String> solveInformation = new HashMap<>();
        solveInformation.put("source", "astrometry.net");
        if (originalResponse != null) {
            solveInformation.put("original_response", originalResponse);
        }
        return new PlateSolveResult(false, failureReason, "", solveInformation);
    }

    private HttpRequest.Builder requestBuilder(String path, Context context) {
        Duration timeout = requestTimeout;
        if (context != null && context.remaining().compareTo(timeout) < 0) timeout = context.remaining();
        return HttpRequest.newBuilder(serviceBaseUri.resolve(path)).timeout(timeout);
    }

    private HttpResponse<String> sendRequest(HttpRequest request, Context context) throws IOException, InterruptedException {
        if (context != null) context.checkCancelled();
        HttpResponse<String> response;
        try {
            response = client.send(request, BodyHandlers.ofString());
        } catch (HttpTimeoutException timeout) {
            if (context != null && request.timeout().orElse(requestTimeout).compareTo(requestTimeout) < 0) {
                context.pause(context.remaining());
            }
            throw timeout;
        }
        if (context != null) context.checkCancelled();
        if (response.statusCode() >= 400) {
            throw new IOException("Astrometry.net request failed with HTTP status " + response.statusCode());
        }
        return response;
    }

    private boolean isFitsFile(File imageFile) {
        String[] acceptedFileTypes = {"fits", "fit", "fts"};
        String lowercaseName = imageFile.getName().toLowerCase();
        for (String acceptedFileEnd : acceptedFileTypes) {
            if (lowercaseName.endsWith(acceptedFileEnd)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Will update the provided SubmitFileRequest parameters with values from the FITS header
     * It will only update the parameters if a corresponding value is not present in the SubmitFileRequest object.
     * @param fitsFile the FITS file
     * @param parameters the solve parameters
     * @return an updated SubmitFileRequest object
     * @throws FitsException if the FITS file cannot be parsed
     * @throws IOException if the FITS file cannot be read
     */
    private SubmitFileRequest updateFromFitsHeader(File imageFile, SubmitFileRequest parameters) throws FitsException, IOException {
        try (Fits fitsImageFile = new Fits(imageFile)) {
            Header fitsHeader = fitsImageFile.getHDU(0).getHeader();
            Cursor<String, HeaderCard> iter = fitsHeader.iterator();
            while (iter.hasNext()) {
                HeaderCard fitsHeaderCard = iter.next();
                String headerKeyword = fitsHeaderCard.getKey();
                String headerKeywordValue = fitsHeaderCard.getValue();

                logger.fine("Processing fits header keyword:" + headerKeyword + " which has value:" + headerKeywordValue);
                switch (headerKeyword) {
                    case "OBJCTRA": {
                        logger.fine("found keyword OBJCTRA");
                        if (parameters.getCenter_ra() == 0.0f) {
                            try {
                                parameters.setCenter_ra(getRA(headerKeywordValue));
                                logger.fine("updated with value:" + parameters.getCenter_ra());
                            } catch (IllegalArgumentException ex) {
                                logger.warning(ex.getMessage() + " will not update RA value");
                            }
                        } else {
                            logger.fine("will not update since the provided object has value :" + parameters.getCenter_ra());
                        }
                        break;
                    }
                    case "OBJCTDEC": {
                        logger.fine("found keyword OBJCTDEC");
                        if (parameters.getCenter_dec() == 0.0f) {
                            try {
                                parameters.setCenter_dec(getDEC(headerKeywordValue));
                                logger.fine("updated with value:" + parameters.getCenter_dec());
                            } catch (IllegalArgumentException ex) {
                                logger.warning(ex.getMessage() + " will not update DEC value");
                            }
                        } else {
                            logger.fine("will not update since the provided object has value :" + parameters.getCenter_dec());
                        }
                        break;
                    }
                    default: {
                    }
                }
            }
        }

        return parameters;
    }


    /**
     * Will return the RA in float format from a String representation
     * @param RAString right ascension expressed as hour minute second components
     * @return right ascension in degrees
     */
    private float getRA(String RAString) {
        String RAStringProc = RAString.replaceAll("'", "");
        RAStringProc = RAStringProc.replaceAll("\"", "");

        String[] coordinates = RAStringProc.split(" ");
        if (coordinates.length != 3) {
            throw new IllegalArgumentException("Cannot decode " + RAString + " as RA");
        }

        float result = Float.parseFloat(coordinates[0]) * 15;
        result += (Float.parseFloat(coordinates[1]) * 0.25f);
        result += (Float.parseFloat(coordinates[2]) * 0.00417f);

        return result;
    }

    /**
     * Will return the DEC in float format from a String representation
     * @param DECString declination expressed as degree minute second components
     * @return declination in degrees
     */
    private float getDEC(String DECString) {
        String DECStringProc = DECString.replaceAll("'", "");
        DECStringProc = DECStringProc.replaceAll("\"", "");

        String[] coordinates = DECStringProc.split(" ");
        if (coordinates.length != 3) {
            throw new IllegalArgumentException("Cannot decode " + DECString + "as DEC");
        }

        float result = Float.parseFloat(coordinates[0]);
        if (result < 0) {
            result -= Float.parseFloat(coordinates[1]) / 60;
        } else {
            result += Float.parseFloat(coordinates[1]) / 60;
        }
        if (result < 0) {
            result -= Float.parseFloat(coordinates[2]) / 3600;
        } else {
            result += Float.parseFloat(coordinates[2]) / 3600;
        }

        return result;
    }

    private static String sanitizeApiKey(String configuredApiKey) {
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            return DEFAULT_API_KEY;
        }
        return configuredApiKey;
    }

    private static Duration sanitizeDuration(Duration configuredDuration, Duration fallback) {
        if (configuredDuration == null || configuredDuration.isZero() || configuredDuration.isNegative()) {
            return fallback;
        }
        return configuredDuration;
    }

}
