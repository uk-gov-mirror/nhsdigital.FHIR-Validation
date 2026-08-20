package com.nhs;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CapabilityStatementValidationConfig {

    @Autowired
    private FhirContext myFhirContext;

    @Autowired
    private DaoRegistry myDaoRegistry;

    @Bean
    public FilterRegistrationBean<ValidateProfileDefaultingFilter> validateProfileDefaultingFilter() {
        ValidateProfileDefaultingFilter filter =
                new ValidateProfileDefaultingFilter(myFhirContext, myDaoRegistry);

        FilterRegistrationBean<ValidateProfileDefaultingFilter> registration =
                new FilterRegistrationBean<>(filter);

        registration.addUrlPatterns("/fhir/*");
        registration.setOrder(1);
        registration.setName("validateProfileDefaultingFilter");

        System.out.println("[CONFIG] ValidateProfileDefaultingFilter registered for /fhir/*");
        return registration;
    }
}