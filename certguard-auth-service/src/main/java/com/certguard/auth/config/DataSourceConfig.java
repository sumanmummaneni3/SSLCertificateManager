package com.certguard.auth.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Configures the secondary mainDataSource only.
 * The primary auth DataSource (certguard_auth) is left to Spring Boot autoconfiguration
 * so that Flyway and JPA pick it up correctly without manual wiring.
 */
@Configuration
public class DataSourceConfig {

    // ── Main certguard DB (JDBC-only, no JPA) ────────────────────────────────

    @Bean("mainDataSource")
    public HikariDataSource mainDataSource(
            @Value("${main.datasource.url}") String url,
            @Value("${main.datasource.username}") String username,
            @Value("${main.datasource.password}") String password) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .driverClassName("org.postgresql.Driver")
                .build();
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(30_000);
        ds.setPoolName("MainHikariPool");
        return ds;
    }

    @Bean("mainJdbcTemplate")
    public JdbcTemplate mainJdbcTemplate(@Qualifier("mainDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
