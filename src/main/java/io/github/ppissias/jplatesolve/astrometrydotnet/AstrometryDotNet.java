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
import io.github.ppissias.jplatesolve.PlateSolveResult;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * main class interfacing the astrometry.net services
 *
 * @author Petros Pissias
 *
 */
public class AstrometryDotNet implements AutoCloseable {

    //astrometry.net Service URIs
    private static final String LOGIN_URI = "https://nova.astrometry.net/api/login";
    private static final String SUBMIT_FILE_URI = "https://nova.astrometry.net/api/upload";
    private static final String SUBMISSION_PROGRESS_URI = "https://nova.astrometry.net/api/submissions/";//+SUBID
    private static final String JOB_PROGRESS_URI = "https://nova.astrometry.net/api/jobs/"; //+JOBID/info
    private static final String WCS_BASE_URI = "https://nova.astrometry.net/wcs_file/"; //+JOBID
    //links with info
    private static final String ANNOTATED_IMAGE_LINK = "https://nova.astrometry.net/annotated_display/"; //+JOBID
    private static final String RESULTS_PAGE_LINK = "https://nova.astrometry.net/status/"; //+StatusID

    public static final String DEFAULT_API_KEY = "XXXXXXXX";
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_SOLVE_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);

    //Astrometry.net session ID, after logging
    private String sessionID = null;

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

        ExecutorService configuredExecutor = effectiveConfig.getExecutorService();
        if (configuredExecutor == null) {
            this.executor = Executors.newFixedThreadPool(3);
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
    public synchronized String getSessionID() {
        return this.sessionID;
    }

    public String getApiKey() {
        return apiKey;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public Duration getSolveTimeout() {
        return solveTimeout;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    /**
     * Logs in to astrometry.net and obtains a session ID
     * @throws IOException if the login request fails
     * @throws InterruptedException if the login request is interrupted
     */
    public synchronized void login() throws IOException, InterruptedException {
        LoginRequest loginReq = LoginRequest.builder().withApikey(apiKey).build();

        logger.fine("Sending Login request [JSON]:" + gson.toJson(loginReq));

        var builder = new StringBuilder();
        builder.append(URLEncoder.encode("request-json", StandardCharsets.UTF_8));
        builder.append("=");
        builder.append(URLEncoder.encode(gson.toJson(loginReq), StandardCharsets.UTF_8));

        logger.fine("Formatted HTTP POST request:" + builder);

        HttpRequest request = requestBuilder(LOGIN_URI)
                .POST(HttpRequest.BodyPublishers.ofString(builder.toString()))
                .headers("Content-Type", "application/x-www-form-urlencoded")
                .build();

        HttpResponse<String> response = sendRequest(request, LOGIN_URI);
        LoginResponse loginResponse = gson.fromJson(response.body(), LoginResponse.class);
        logger.fine("Transformed Login Response:" + loginResponse);

        if (loginResponse == null || !"success".equals(loginResponse.getStatus())
                || loginResponse.getSession() == null || loginResponse.getSession().isBlank()) {
            throw new IOException("Astrometry.net login failed: " + response.body());
        }

        this.sessionID = loginResponse.getSession();
    }


    /**
     * Makes a blind solve request to Astrometry.net
     * It will login if the user has not yet logged in. The result may take up to 5-10 minutes
     * depending on how busy astrometry.net currently is. It will upload the provided file
     * and return a Future that will eventually have the {@link PlateSolveResult}
     * @param imageFile the image file
     * @return a Future that can be used to obtain the solve result
     * @throws IOException if login fails before the solve task is submitted
     * @throws InterruptedException if login is interrupted before the solve task is submitted
     */
    public Future<PlateSolveResult> blindSolve(File imageFile) throws IOException, InterruptedException {
        logger.fine("Will try to blind solve:" + imageFile.getAbsolutePath());
        ensureLoggedIn();

        SubmitFileRequest fileBlindSolveRequest = SubmitFileRequest.builder()
                .withSession(sessionID)
                .withPublicly_visible("y")
                .build();

        return executor.submit(() -> performSolve(imageFile, fileBlindSolveRequest));
    }


    /**
     * Makes a "custom" solve of the image. A custom solve allows the user
     * to specify any number of parameters he wants that will influence the processing of the image.
     * All user parameters are provided in the {@link SubmitFileRequest} parameter.
     * These are the parameters from astrometry.net.
     * If the image is a FITS file then the following properties from the header will be read and provided.
     * Priority for the parameters is on the {@link SubmitFileRequest} parameter in case a paeameter
     * is also specified in the FITS header.
     * @param imageFile the file to be solved.
     * @param inputParameters request parameters to send to Astrometry.net
     * @return a Future that can be used to obtain the solve result
     * @throws IOException if login or FITS parsing fails before the solve task is submitted
     * @throws InterruptedException if login is interrupted before the solve task is submitted
     * @throws FitsException if FITS metadata parsing fails
     */
    public Future<PlateSolveResult> customSolve(File imageFile, SubmitFileRequest inputParameters) throws IOException, InterruptedException, FitsException {
        logger.fine("Will try to custom solve:" + imageFile.getAbsolutePath() + " with initial parameters" + inputParameters);
        if (inputParameters.getSession() == null || inputParameters.getSession().isBlank()) {
            ensureLoggedIn();
            inputParameters.setSession(this.sessionID);
        }

        SubmitFileRequest updatedParameters;
        if (isFitsFile(imageFile)) {
            updatedParameters = updateFromFitsHeader(imageFile, inputParameters);
        } else {
            updatedParameters = inputParameters;
        }

        if (updatedParameters.getScale_units() == null) {
            logger.info("did not find any scale units, perhaps specify scale-low=0.1 scale-high=180.0 scale-units=degwidth");
        }

        if (updatedParameters.getDownsample_factor() == 0.0f) {
            logger.info("did not find any downsample factor, perhaps specify downsample=2");
        }

        if (updatedParameters.getRadius() == 0.0f) {
            logger.info("did not find any radius, perhaps specify radius=10");
        }

        return executor.submit(() -> performSolve(imageFile, updatedParameters));
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private PlateSolveResult performSolve(File imageFile, SubmitFileRequest inputParameters) throws InterruptedException {
        Instant deadline = Instant.now().plus(solveTimeout);
        String boundary = UUID.randomUUID().toString();
        String requestJson = gson.toJson(inputParameters);

        logger.fine("JSON parameter for solve request:" + requestJson);

        try {
            HttpRequest submitRequest = requestBuilder(SUBMIT_FILE_URI)
                    .POST(SubmitFileBodyPublisher.getBodyPublisher(imageFile, requestJson, boundary))
                    .headers("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .build();

            HttpResponse<String> response = sendRequest(submitRequest, SUBMIT_FILE_URI);
            SubmitFileResponse submitFileResponse = gson.fromJson(response.body(), SubmitFileResponse.class);
            logger.fine("Transformed file submit Response:" + submitFileResponse);

            if (submitFileResponse == null || !"success".equals(submitFileResponse.getStatus())) {
                logger.warning("Submit request was not succesful");
                return failureResult("Submit request was not successful", response.body());
            }

            int jobID = waitForJobId(submitFileResponse, deadline);
            JobResultResponse jobResResponse = waitForJobResult(jobID, deadline);
            return mapSolveResult(submitFileResponse, jobID, jobResResponse);
        } catch (SolveTimeoutException e) {
            logger.warning(e.getMessage());
            return failureResult(e.getMessage(), null);
        } catch (IOException e) {
            logger.warning("Astrometry.net solve failed: " + e.getMessage());
            return failureResult("Astrometry.net solve failed: " + e.getMessage(), null);
        } catch (RuntimeException e) {
            logger.warning("Astrometry.net solve returned an unexpected response: " + e.getMessage());
            return failureResult("Astrometry.net solve returned an unexpected response: " + e.getMessage(), null);
        }
    }

    private int waitForJobId(SubmitFileResponse submitFileResponse, Instant deadline)
            throws IOException, InterruptedException, SolveTimeoutException {
        while (true) {
            ensureSolveStillRunning(deadline, "Timed out waiting for Astrometry.net to assign a job ID");
            logger.fine("Will check if the request is being processed");

            HttpRequest request = requestBuilder(SUBMISSION_PROGRESS_URI + submitFileResponse.getSubid())
                    .GET()
                    .build();
            HttpResponse<String> response = sendRequest(request, SUBMISSION_PROGRESS_URI + submitFileResponse.getSubid());

            if (response.body().contains("[null]")) {
                sleepBeforeRetry(deadline, "JOB id not yet available");
                continue;
            }

            SubmissionProgressResponse subProgResponse = gson.fromJson(response.body(), SubmissionProgressResponse.class);
            logger.fine("Transformed SubmissionProgressResponse :" + subProgResponse);

            int[] jobs = subProgResponse == null ? null : subProgResponse.getJobs();
            if (jobs != null && jobs.length > 0) {
                int jobID = jobs[0];
                logger.fine("JOB id became available:" + jobID);
                return jobID;
            }

            sleepBeforeRetry(deadline, "JOB id not yet available");
        }
    }

    private JobResultResponse waitForJobResult(int jobID, Instant deadline)
            throws IOException, InterruptedException, SolveTimeoutException {
        while (true) {
            ensureSolveStillRunning(deadline, "Timed out waiting for Astrometry.net job " + jobID + " to complete");
            logger.fine("Will check if job has been completed");

            HttpRequest request = requestBuilder(JOB_PROGRESS_URI + jobID + "/info")
                    .GET()
                    .build();
            HttpResponse<String> response = sendRequest(request, JOB_PROGRESS_URI + jobID + "/info");

            if (response.body().contains("[null]")) {
                sleepBeforeRetry(deadline, "JOB not yet completed");
                continue;
            }

            JobResultResponse jobResResponse = gson.fromJson(response.body(), JobResultResponse.class);
            logger.fine("Transformed JobResultResponse :" + jobResResponse);

            String returnedJobStatus = jobResResponse == null ? null : jobResResponse.getStatus();
            if ("success".equals(returnedJobStatus) || "failure".equals(returnedJobStatus)) {
                logger.fine("JOB completed");
                return jobResResponse;
            }

            sleepBeforeRetry(deadline, "JOB not yet completed");
        }
    }

    private PlateSolveResult mapSolveResult(SubmitFileResponse submitFileResponse, int jobID, JobResultResponse jobResResponse) {
        if (jobResResponse != null && "success".equals(jobResResponse.getStatus())) {
            logger.fine("Image solving was sucecesful :" + jobResResponse);

            Map<String, String> solveInformation = new HashMap<>();
            solveInformation.put("source", "astrometry.net");
            solveInformation.put("original_response", gson.toJson(jobResResponse));
            solveInformation.put("annotated_image_link", ANNOTATED_IMAGE_LINK + jobID);
            solveInformation.put("status_page_link", RESULTS_PAGE_LINK + submitFileResponse.getSubid());
            solveInformation.put("wcs_link", WCS_BASE_URI + jobID);

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

    private HttpRequest.Builder requestBuilder(String uri) {
        return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(requestTimeout);
    }

    private HttpResponse<String> sendRequest(HttpRequest request, String requestUri) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
        logger.fine("HTTP request to " + requestUri + " succesfully made");
        logger.fine("Response header:" + response.headers());
        logger.fine("Response body:" + response.body());
        logger.fine("Response status code:" + response.statusCode());
        if (response.statusCode() >= 400) {
            throw new IOException("Astrometry.net request to " + requestUri + " failed with status code " + response.statusCode());
        }
        return response;
    }

    private void ensureSolveStillRunning(Instant deadline, String timeoutMessage)
            throws InterruptedException, SolveTimeoutException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Astrometry.net solve was interrupted");
        }
        if (!Instant.now().isBefore(deadline)) {
            throw new SolveTimeoutException(timeoutMessage);
        }
    }

    private void sleepBeforeRetry(Instant deadline, String waitMessage)
            throws InterruptedException, SolveTimeoutException {
        ensureSolveStillRunning(deadline, waitMessage);
        long remainingMillis = Math.max(1L, Duration.between(Instant.now(), deadline).toMillis());
        long sleepMillis = Math.min(pollInterval.toMillis(), remainingMillis);
        logger.fine(waitMessage);
        Thread.sleep(Math.max(1L, sleepMillis));
    }

    private synchronized void ensureLoggedIn() throws IOException, InterruptedException {
        if (sessionID == null || sessionID.isBlank()) {
            login();
        }
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

    private static final class SolveTimeoutException extends Exception {
        private SolveTimeoutException(String message) {
            super(message);
        }
    }
}
