package com.certguard.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Manual Flyway wiring.
 *
 * <p>Spring Boot 4 split Flyway auto-configuration into a module that is NOT on this
 * project's classpath (there is no {@code spring-boot-flyway} dependency, and
 * {@code spring-boot-autoconfigure} no longer carries the Flyway entry). Without that
 * auto-configuration, Boot does not run migrations and does not order the JPA
 * {@code EntityManagerFactory} after them, so this bean wires Flyway by hand.
 *
 * <p>The {@code migrate} init method applies the migrations. Critically,
 * {@link EntityManagerFactoryDependsOnPostProcessor} forces the EntityManagerFactory
 * to be created only AFTER the {@code flyway} bean has migrated. Without that
 * dependency the bean-init order is undefined: a JPA-touching bean (e.g.
 * {@code TokenRevocationService}) can initialise before the schema exists and fail on
 * an empty database with {@code relation "..." does not exist}. This is latent on a
 * pre-populated DB and only surfaces on a fresh/empty database.
 */
@Configuration
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .load();
    }

    /**
     * Makes the JPA EntityManagerFactory depend on (be created after) the {@code flyway}
     * bean, guaranteeing migrations run before Hibernate validates or uses the schema.
     * Declared {@code static} so it is instantiated early as a BeanFactoryPostProcessor.
     */
    @Bean
    public static EntityManagerFactoryDependsOnPostProcessor flywayEntityManagerFactoryDependsOnPostProcessor() {
        return new EntityManagerFactoryDependsOnPostProcessor("flyway");
    }
}
