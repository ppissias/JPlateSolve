package io.github.ppissias.jplatesolve.astrometrydotnet;

import java.time.Duration;
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
    private final Duration requestTimeout;
    private final Duration solveTimeout;
    private final Duration pollInterval;
    private final ExecutorService executorService;

    private AstrometryDotNetConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.requestTimeout = builder.requestTimeout;
        this.solveTimeout = builder.solveTimeout;
        this.pollInterval = builder.pollInterval;
        this.executorService = builder.executorService;
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

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public Duration getSolveTimeout() {
        return solveTimeout;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Builder for {@link AstrometryDotNetConfig}.
     */
    public static final class Builder {
        private String apiKey;
        private Duration requestTimeout;
        private Duration solveTimeout;
        private Duration pollInterval;
        private ExecutorService executorService;

        private Builder() {
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

        /**
         * Sets the timeout for a single HTTP request.
         *
         * @param requestTimeout per-request timeout
         * @return this builder
         */
        public Builder withRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
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
