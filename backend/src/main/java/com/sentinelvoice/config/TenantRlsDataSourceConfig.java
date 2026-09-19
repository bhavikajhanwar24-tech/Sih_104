package com.sentinelvoice.config;

import com.sentinelvoice.security.TenantAwareDataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;

@Configuration
public class TenantRlsDataSourceConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static BeanPostProcessor tenantAwareDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof DataSource ds && !(bean instanceof TenantAwareDataSource)) {
                    // Wrap the primary pool only (Hikari). Flyway uses its own DataSource bean in Boot 3.
                    if ("dataSource".equals(beanName)) {
                        return new TenantAwareDataSource(ds);
                    }
                }
                return bean;
            }
        };
    }
}
