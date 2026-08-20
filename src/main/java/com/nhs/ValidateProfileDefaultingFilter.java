package com.nhs;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.springframework.web.filter.OncePerRequestFilter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Intercepts POST {base}/{ResourceType}/$validate calls. If the resource being validated
 * doesn't already declare a meta.profile, injects one before the request reaches HAPI's
 * built-in $validate operation, using this priority:
 *
 *   1. The resource's own declared meta.profile - left untouched if present.
 *   2. The profile declared for this resource type in the server's stored CapabilityStatement
 *      (assumes exactly one CapabilityStatement resource is present in the repository).
 *   3. Neither - request passes through unmodified.
 *
 * Handles both JSON (raw resource, and Parameters-wrapped) and XML (raw resource) bodies.
 */
public class ValidateProfileDefaultingFilter extends OncePerRequestFilter {

    private static final Pattern VALIDATE_PATH_PATTERN = Pattern.compile("/([A-Z][A-Za-z]+)/\\$validate/?$");
    private static final String FHIR_NS = "http://hl7.org/fhir";

    private final FhirContext myFhirContext;
    private final DaoRegistry myDaoRegistry;
    private final ObjectMapper myJsonMapper = new ObjectMapper();

    private volatile Map<String, String> myResourceTypeToProfile;

    public ValidateProfileDefaultingFilter(FhirContext theFhirContext, DaoRegistry theDaoRegistry) {
        myFhirContext = theFhirContext;
        myDaoRegistry = theDaoRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String resourceType = extractResourceType(request.getRequestURI());
        String contentType = request.getContentType();

        if (resourceType == null || contentType == null) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean isJson = contentType.contains("json");
        boolean isXml = contentType.contains("xml");

        if (!isJson && !isXml) {
            filterChain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request);

        try {
            if (isJson) {
                rewriteJson(wrapped, resourceType);
            } else {
                rewriteXml(wrapped, resourceType);
            }
        } catch (Exception e) {
            // Don't block validation if our rewriting logic fails - just pass the original body through.
            System.err.println("[VALIDATE-DEFAULT] Failed to inspect/rewrite body: " + e.getMessage());
        }

        filterChain.doFilter(wrapped, response);
    }

    private String extractResourceType(String requestUri) {
        Matcher m = VALIDATE_PATH_PATTERN.matcher(requestUri);
        return m.find() ? m.group(1) : null;
    }

    // ---------------------------------------------------------------- JSON

    private void rewriteJson(CachedBodyHttpServletRequest wrapped, String resourceType) throws IOException {
        JsonNode root = myJsonMapper.readTree(wrapped.getBodyBytes());
        ObjectNode target = findTargetResourceNode(root);

        if (target == null || hasProfileJson(target)) {
            return;
        }

        String profile = resolveFromCapabilityStatement(resourceType);
        if (profile == null) {
            return;
        }

        addProfileJson(target, profile);
        wrapped.setBody(myJsonMapper.writeValueAsBytes(root));
        System.out.println("[VALIDATE-DEFAULT] No meta.profile on " + resourceType + " (JSON) - defaulting to " + profile);
    }

    /** Handles both a raw resource body, and a Parameters-wrapped body with a "resource" parameter. */
    private ObjectNode findTargetResourceNode(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode resourceTypeNode = root.get("resourceType");
        if (resourceTypeNode != null && "Parameters".equals(resourceTypeNode.asText())) {
            JsonNode parameters = root.get("parameter");
            if (parameters != null && parameters.isArray()) {
                for (JsonNode param : parameters) {
                    JsonNode name = param.get("name");
                    if (name != null && "resource".equals(name.asText())) {
                        JsonNode resourceNode = param.get("resource");
                        return resourceNode != null && resourceNode.isObject() ? (ObjectNode) resourceNode : null;
                    }
                }
            }
            return null;
        }
        return (ObjectNode) root;
    }

    private boolean hasProfileJson(ObjectNode target) {
        JsonNode meta = target.get("meta");
        if (meta == null) {
            return false;
        }
        JsonNode profile = meta.get("profile");
        return profile != null && profile.isArray() && !profile.isEmpty();
    }

    private void addProfileJson(ObjectNode target, String profileUrl) {
        ObjectNode meta = target.has("meta") && target.get("meta").isObject()
                ? (ObjectNode) target.get("meta")
                : target.putObject("meta");

        ArrayNode profiles = meta.has("profile") && meta.get("profile").isArray()
                ? (ArrayNode) meta.get("profile")
                : meta.putArray("profile");

        profiles.add(profileUrl);
    }

    // ----------------------------------------------------------------- XML

    private void rewriteXml(CachedBodyHttpServletRequest wrapped, String resourceType) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Harden against XXE - this only ever parses resources we control/generate in CI, but no reason not to.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(wrapped.getBodyAsString())));

        Element root = doc.getDocumentElement();
        Element metaEl = getDirectChildElement(root, "meta");

        boolean hasProfile = metaEl != null && metaEl.getElementsByTagNameNS(FHIR_NS, "profile").getLength() > 0;
        if (hasProfile) {
            return;
        }

        String profile = resolveFromCapabilityStatement(resourceType);
        if (profile == null) {
            return;
        }

        if (metaEl == null) {
            metaEl = doc.createElementNS(FHIR_NS, "meta");
            // FHIR element order: id, meta, implicitRules, language, ... - insert right after id if present,
            // otherwise as the very first child.
            Element idEl = getDirectChildElement(root, "id");
            Node insertBeforeNode = idEl != null ? idEl.getNextSibling() : root.getFirstChild();
            root.insertBefore(metaEl, insertBeforeNode);
        }

        Element profileEl = doc.createElementNS(FHIR_NS, "profile");
        profileEl.setAttribute("value", profile);
        metaEl.appendChild(profileEl);

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));

        wrapped.setBody(writer.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[VALIDATE-DEFAULT] No meta.profile on " + resourceType + " (XML) - defaulting to " + profile);
    }

    private Element getDirectChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && localName.equals(node.getLocalName())
                    && FHIR_NS.equals(node.getNamespaceURI())) {
                return (Element) node;
            }
        }
        return null;
    }

    // ------------------------------------------------------- CapabilityStatement lookup (shared)

    private synchronized String resolveFromCapabilityStatement(String resourceType) {
        if (myResourceTypeToProfile == null || myResourceTypeToProfile.isEmpty()) {
            myResourceTypeToProfile = loadProfileMapFromStoredCapabilityStatement();
        }
        return myResourceTypeToProfile.get(resourceType);
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
                return Collections.emptyMap();
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
            System.err.println("[VALIDATE-DEFAULT] Failed to load CapabilityStatement: " + e.getMessage());
            return Collections.emptyMap();
        }
    }
}