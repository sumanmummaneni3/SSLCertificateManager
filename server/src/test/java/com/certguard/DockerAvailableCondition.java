package com.certguard;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * JUnit 5 ExecutionCondition that skips (aborts) the test class when Docker
 * is not available. Applied via @ExtendWith on Testcontainers-backed tests so
 * the build does not fail in environments without a Docker daemon.
 */
public class DockerAvailableCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                return ConditionEvaluationResult.enabled("Docker is available");
            }
        } catch (Exception ignored) {
            // fall through to disabled
        }
        return ConditionEvaluationResult.disabled("Docker is not available — skipping Testcontainers test");
    }
}
