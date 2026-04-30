package com.certguard.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link JwtSecretValidator} prevents context startup when
 * JWT_SECRET is absent or too short.  No Docker or full application context
 * required — uses ApplicationContextRunner for a minimal slice.
 */
class JwtSecretValidatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtSecretValidator.class);

    @Test
    void contextFailsWhenJwtSecretIsBlank() {
        runner.withPropertyValues("app.jwt.secret=")
              .run(ctx -> {
                  assertThat(ctx).hasFailed();
                  Throwable failure = ctx.getStartupFailure();
                  assertThat(failure).isInstanceOfAny(BeanCreationException.class, IllegalStateException.class);
                  // The root cause carries the message; walk the cause chain
                  Throwable root = failure;
                  while (root.getCause() != null) root = root.getCause();
                  assertThat(root.getMessage()).contains("JWT_SECRET");
              });
    }

    @Test
    void contextFailsWhenJwtSecretIsTooShort() {
        runner.withPropertyValues("app.jwt.secret=tooshort12")
              .run(ctx -> {
                  assertThat(ctx).hasFailed();
                  Throwable failure = ctx.getStartupFailure();
                  assertThat(failure).isInstanceOfAny(BeanCreationException.class, IllegalStateException.class);
                  Throwable root = failure;
                  while (root.getCause() != null) root = root.getCause();
                  assertThat(root.getMessage()).contains("JWT_SECRET");
              });
    }

    @Test
    void contextStartsWhenJwtSecretIsLongEnough() {
        runner.withPropertyValues("app.jwt.secret=aaaabbbbccccddddeeeeffffgggghhhh")
              .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
