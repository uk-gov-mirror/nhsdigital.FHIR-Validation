package com.nhs;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.util.ParametersUtil;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Runs only against $validate calls. Does NOT perform validation itself - it simply ensures
 * the resource being validated has a meta.profile set before HAPI's built-in $validate operation
 * processes it, using this priority:
 *
 *   1. The resource's own declared meta.profile, if present - left untouched.
 *   2. The profile declared for this resource type in the server's stored CapabilityStatement
 *      (assumes exactly one CapabilityStatement resource is present in the repository).
 *   3. The base/core FHIR StructureDefinition for the resource type, as a last resort - in
 *      practice this just means we leave meta.profile empty and let $validate fall back to
 *      base structural validation, which is its normal behaviour with no profile declared.
 *
 * Does NOT hook resource create/update storage pointcuts, so it has no effect on plain
 * POST/PUT calls used to load assets - only on $validate.
 */
@Interceptor
public class CapabilityStatementProfileFallbackInterceptor {

    private static final Logger ourLog = LoggerFactory.getLogger(CapabilityStatementProfileFallbackInterceptor.class);

    private static final String VALIDATE_OPERATION_NAME = "$validate";

    private final FhirContext myFhirContext;
    private final DaoRegistry myDaoRegistry;

    // Lazily loaded and cached once non-empty - one CapabilityStatement per run, loaded during
    // the asset phase which always completes before $validate calls begin.
    private volatile Map<String, String> myResourceTypeToProfile;

    public CapabilityStatementProfileFallbackInterceptor(FhirContext theFhirContext, DaoRegistry theDaoRegistry) {
        myFhirContext = theFhirContext;
        myDaoRegistry = theDaoRegistry;
    }

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
    public void preHandle(RequestDetails theRequestDetails) {
        if (!VALIDATE_OPERATION_NAME.equals(theRequestDetails.getOperation())) {
            return;
        }

        IBaseResource target = extractTargetResource(theRequestDetails);
        if (target == null) {
            ourLog.warn("$validate call received but no target resource could be extracted from RequestDetails - "
                    + "profile fallback will not run for this request");
            return;
        }

        if (!target.getMeta().getProfile().isEmpty()) {
            // Resource already declares a profile - honour it, don't override.
            return;
        }

        String resourceType = myFhirContext.getResourceType(target);
        String profile = resolveFromCapabilityStatement(resourceType);
        if (profile != null) {
            target.getMeta().addProfile(profile);
            ourLog.debug("No meta.profile declared for {} - defaulting to {} from CapabilityStatement",
                    resourceType, profile);
        }
        // If null: no CapabilityStatement entry for this type either - leave meta.profile empty
        // and let $validate fall through to base structural validation for the type.
    }

    /**
     * $validate can be called either with the resource posted directly, or wrapped in a
     * Parameters resource alongside "profile"/"mode" parameters. Handle both.
     */
    private IBaseResource extractTargetResource(RequestDetails theRequestDetails) {
        IBaseResource resource = theRequestDetails.getResource();
        if (resource == null) {
            return null;
        }

        String type = myFhirContext.getResourceType(resource);
        if ("Parameters".equals(type)) {
            Optional<IBaseResource> wrapped = ParametersUtil.getNamedParameterResource(
                    myFhirContext, (org.hl7.fhir.instance.model.api.IBaseParameters) resource, "resource");
            return wrapped.orElse(null);
        }
        return resource;
    }

    private synchronized String resolveFromCapabilityStatement(String theResourceType) {
        if (myResourceTypeToProfile == null || myResourceTypeToProfile.isEmpty()) {
            myResourceTypeToProfile = loadProfileMapFromStoredCapabilityStatement();
        }
        return myResourceTypeToProfile.get(theResourceType);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadProfileMapFromStoredCapabilityStatement() {
        try {
            IFhirResourceDao<CapabilityStatement> dao =
                    (IFhirResourceDao<CapabilityStatement>) myDaoRegistry.getResourceDao("CapabilityStatement");

            IBundleProvider results = dao.search(SearchParameterMap.newSynchronous());
            int size = results.size() != null ? results.size() : 1;
            List<CapabilityStatement> resources = (List<CapabilityStatement>) (List<?>) results.getResources(0, size);

            if (resources.isEmpty()) {
                ourLog.warn("No CapabilityStatement found in the repository yet - profile fallback unavailable");
                return Collections.emptyMap();
            }
            if (resources.size() > 1) {
                ourLog.warn("Multiple CapabilityStatement resources found - using the first one returned");
            }

            CapabilityStatement cs = resources.get(0);
            return cs.getRest().stream()
                    .flatMap(rest -> rest.getResource().stream())
                    .filter(CapabilityStatement.CapabilityStatementRestResourceComponent::hasProfile)
                    .collect(Collectors.toMap(
                            CapabilityStatement.CapabilityStatementRestResourceComponent::getType,
                            CapabilityStatement.CapabilityStatementRestResourceComponent::getProfile,
                            (a, b) -> a));
        } catch (Exception e) {
            ourLog.warn("Failed to load CapabilityStatement for profile fallback: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}