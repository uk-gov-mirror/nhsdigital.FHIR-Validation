package com.nhs;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TerminologyFilterConfig {

    @Autowired
    private TerminologyInterceptor tokenInterceptor;

    @Bean
    public FilterRegistrationBean<TerminologyOperationInterceptor> terminologyFilter() {
        TerminologyOperationInterceptor filter =
                new TerminologyOperationInterceptor(tokenInterceptor);

        FilterRegistrationBean<TerminologyOperationInterceptor> registration =
                new FilterRegistrationBean<>(filter);

        registration.addUrlPatterns("/fhir/*");
        registration.setOrder(1);
        registration.setName("terminologyOperationFilter");

        System.out.println("[CONFIG] Terminology proxy filter registered for /fhir/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<OpenApiCustomizer> openApiFilter() {
        FilterRegistrationBean<OpenApiCustomizer> registration =
                new FilterRegistrationBean<>(new OpenApiCustomizer());

        registration.addUrlPatterns("/fhir/api-docs", "/fhir/api-docs/*");
        registration.setOrder(2);
        registration.setName("openApiCustomizerFilter");

        System.out.println("[CONFIG] OpenAPI XML customizer filter registered.");
        return registration;
    }
}