package com.nhs;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

@Configuration
public class ProfileValidationConfig {

    @Autowired
    private FhirContext myFhirContext;

    @Autowired
    private DaoRegistry myDaoRegistry;

    @Autowired
    private IInterceptorService myInterceptorService;

    @Bean
    public CapabilityStatementProfileFallbackInterceptor capabilityStatementProfileFallbackInterceptor() {
        return new CapabilityStatementProfileFallbackInterceptor(myFhirContext, myDaoRegistry);
    }

    @EventListener(ContextRefreshedEvent.class)
    public void registerInterceptor(ContextRefreshedEvent event) {
        CapabilityStatementProfileFallbackInterceptor interceptor =
                event.getApplicationContext().getBean(CapabilityStatementProfileFallbackInterceptor.class);
        if (!myInterceptorService.getAllRegisteredInterceptors().contains(interceptor)) {
            myInterceptorService.registerInterceptor(interceptor);
        }
    }
}