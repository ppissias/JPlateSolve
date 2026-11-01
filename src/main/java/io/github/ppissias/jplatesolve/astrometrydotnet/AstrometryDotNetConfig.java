package io.github.ppissias.jplatesolve.astrometrydotnet;

import java.time.Duration;
import java.net.URI;
import java.util.concurrent.ExecutorService;

/**
 * Configuration for {@link AstrometryDotNet}.
 * <p>
 * All properties are optional. Missing or invalid durations are normalized by
 * the client to sensible defaults, and a missing API key causes the built-in
 * guest key to be used. When an executor service is supplied, the caller keeps
 * ownership of that executor and is responsible for shutting it down.
 */
public class AstrometryDotNetConfig {

    private final String apiKey;
    private final Duration connectTimeout;
    private final Duration uploadTimeout;
    private final Duration statusTimeout;
    private final boolean publiclyVisible;
    private final Duration solveTimeout;
    private final Duration pollInterval;
    private final ExecutorService executorService;
    private final URI serviceBaseUri;

    private AstrometryDotNetConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.connectTimeout = builder.connectTimeout;
        this.uploadTimeout = builder.uploadTimeout;
        this.statusTimeout = builder.statusTimeout;
        this.publiclyVisible = builder.publiclyVisible;
        this.solveTimeout = builder.solveTimeout;
        this.pollInterval = builder.pollInterval;
        this.executorService = builder.executorService;
        this.serviceBaseUri = builder.serviceBaseUri;
    }

    /**
     * Creates a new builder for Astrometry.net client configuration.
     *
     * @return configuration builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getApiKey() {
        return apiKey;
    }

    /** Returns the connection-establishment timeout, or null for the client default. */
    public Duration getConnectTimeout() { return connectTimeout; }

    /** Returns the upload request timeout, including its response, or null for the client default. */
    public Duration getUploadTimeout() { return uploadTimeout; }

    /** Returns the login and polling request timeout, or null for the client default. */
    public Duration getStatusTimeout() { return statusTimeout; }

    /** Returns whether uploads deliberately opt into public visibility; false is the default. */
    public boolean isPubliclyVisible() { return publiclyVisible; }

    public Duration getSolveTimeout() {
        return solveTimeout;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    public URI getServiceBaseUri() {
        return serviceBaseUri;
    }

    /**
     * Builder for {@link AstrometryDotNetConfig}.
     */
    public static final class Builder {
        private String apiKey;
        private Duration connectTimeout;
        private Duration uploadTimeout;
        private Duration statusTimeout;
        private boolean publiclyVisible;
        private Duration solveTimeout;
        private Duration pollInterval;
        private ExecutorService executorService;
        private URI serviceBaseUri;

        private Builder() {
        }

        /** Overrides the service root, for example for a private server or loopback tests. */
        public Builder withServiceBaseUri(URI serviceBaseUri) {
            this.serviceBaseUri = serviceBaseUri;
            return this;
        }

        /**
         * Sets the Astrometry.net API key.
         *
         * @param apiKey API key to use; blank values fall back to the default guest
         *        key
         * @return this builder
         */
        public Builder withApiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** Sets the timeout for establishing a new HTTP connection; invalid durations use 10 seconds. */
        public Builder withConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /** Sets the upload request timeout, including its response; invalid durations use 10 minutes. */
        public Builder withUploadTimeout(Duration uploadTimeout) {
            this.uploadTimeout = uploadTimeout;
            return this;
        }

        /** Sets the login and polling request timeout; invalid durations use 60 seconds. */
        public Builder withStatusTimeout(Duration statusTimeout) {
            this.statusTimeout = statusTimeout;
            return this;
        }

        /** Deliberately opts into public uploads; omitting this setting keeps uploads private. */
        public Builder withPubliclyVisible(boolean publiclyVisible) {
            this.publiclyVisible = publiclyVisible;
            return this;
        }

        /**
         * Sets the total time allowed for a solve operation.
         *
         * @param solveTimeout overall solve timeout
         * @return this builder
         */
        public Builder withSolveTimeout(Duration solveTimeout) {
            this.solveTimeout = solveTimeout;
            return this;
        }

        /**
         * Sets how frequently the client polls Astrometry.net for progress.
         *
         * @param pollInterval delay between submission and job status checks
         * @return this builder
         */
        public Builder withPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
            return this;
        }

        /**
         * Sets the executor used for asynchronous solve tasks.
         *
         * @param executorService executor to use; when provided, the caller remains
         *        responsible for shutting it down
         * @return this builder
         */
        public Builder withExecutorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        /**
         * Builds the immutable configuration instance.
         *
         * @return configuration object
         */
        public AstrometryDotNetConfig build() {
            return new AstrometryDotNetConfig(this);
        }
    }
}
