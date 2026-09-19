package com.sentinelvoice.policy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Platform threads for PDFBox/Tika extraction (avoid virtual-thread pinning / silent stalls).
 */
@Configuration
public class PolicyAsyncConfig {

    @Bean(name = "policyExtractionExecutor")
    public AsyncTaskExecutor policyExtractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("policy-extract-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();
        return executor;
    }
}
