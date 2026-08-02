package com.schoolsoft.platform.db;

import com.schoolsoft.platform.tenancy.TenantAwareDataSource;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the Hikari pool, then wraps it in {@link TenantAwareDataSource} so every
 * connection acquired by Spring's transaction manager / Flyway / JdbcTemplate
 * sees the tenant-scoped search_path.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource rawDataSource(DataSourceProperties props) {
        return props.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public DataSource dataSource(
            HikariDataSource raw,
            @Value("${mcb.tenant.default-platform-schema:platform}") String defaultSchema
    ) {
        return new TenantAwareDataSource(raw, defaultSchema);
    }
}
