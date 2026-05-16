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
 *   authDataSource (@Primary) — certguard_auth DB, used by JPA.
 *   mainDataSource            — certguard main DB, used only via mainJdbcTemplate.
 *
 * Flyway is configured with an explicit URL in application.yml so it creates its
 * own connection independently of DataSource bean wiring.
 */
@Configuration
public class DataSourceConfig {

    // ── Auth DB (primary, used by JPA) ────────────────────────────────────────

    @Bean
    @Primary
    public HikariDataSource authDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .driverClassName("org.postgresql.Driver")
                .build();
        ds.setPoolName("AuthHikariPool");
        return ds;
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
