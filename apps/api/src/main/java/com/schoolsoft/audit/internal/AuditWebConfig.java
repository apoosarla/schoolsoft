package com.schoolsoft.audit.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuditWebConfig implements WebMvcConfigurer {

    private final AuditInterceptor interceptor;
    private final OperatorAuditInterceptor operators;

    public AuditWebConfig(AuditInterceptor interceptor, OperatorAuditInterceptor operators) {
        this.interceptor = interceptor;
        this.operators = operators;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/v1/**");
        registry.addInterceptor(operators).addPathPatterns("/v1/**");
    }
}
