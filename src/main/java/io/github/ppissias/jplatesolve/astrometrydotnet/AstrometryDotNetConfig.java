package io.github.ppissias.jplatesolve.astrometrydotnet;

import java.time.Duration;
import java.util.concurrent.ExecutorService;

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

    public static final class Builder {
        private String apiKey;
        private Duration requestTimeout;
        private Duration solveTimeout;
        private Duration pollInterval;
        private ExecutorService executorService;

        private Builder() {
        }

        public Builder withApiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder withRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder withSolveTimeout(Duration solveTimeout) {
            this.solveTimeout = solveTimeout;
            return this;
        }

        public Builder withPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
            return this;
        }

        public Builder withExecutorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        public AstrometryDotNetConfig build() {
            return new AstrometryDotNetConfig(this);
        }
    }
}
