package com.certguard.auth.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Configures two DataSources:
 *   authDataSource (@Primary) — certguard_auth DB, used by JPA/Flyway.
 *   mainDataSource            — certguard main DB, used only via mainJdbcTemplate (plain JDBC).
 *
 * Defining @Primary here causes Spring Boot's DataSource auto-configuration to back off,
 * so JPA and Flyway will pick up authDataSource automatically.
 */
@Configuration
public class DataSourceConfig {

    // ── Auth DB (primary, used by JPA + Flyway) ──────────────────────────────

    @Bean
    @Primary
    public HikariDataSource authDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .driverClassName("org.postgresql.Driver")
                .build();
    }

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
