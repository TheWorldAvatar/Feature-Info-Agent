package com.cmclinnovations.featureinfo.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.cmclinnovations.stack.clients.blazegraph.BlazegraphEndpointConfig;
import com.cmclinnovations.stack.clients.docker.ContainerClient;
import com.cmclinnovations.stack.clients.ontop.OntopEndpointConfig;
import com.cmclinnovations.stack.clients.postgis.PostGISEndpointConfig;
import uk.ac.cam.cares.jps.base.query.RemoteStoreClient;

/**
 * This class handles interactions with the TWA Stack through the
 * stack client library.
 */
public class StackInteractor extends ContainerClient {

    /**
     * Logger for reporting info/errors.
     */
    private static final Logger LOGGER = LogManager.getLogger(StackInteractor.class);

    /**
     * Cache of RDB config, as we need to refer to it later.
     */
    private static PostGISEndpointConfig RDB_CONFIG;

    /**
     * Cache of the "kb" namespace blazegraph endpoint, as we need to refer to it
     * later.
     */
    private static StackEndpoint KB_BLAZEGRAPH_ENDPOINT;

    /**
     * Pool of parsed endpoint entries.
     */
    private final List<StackEndpoint> endpoints;

    /**
     * Connection to KG.
     */
    private RemoteStoreClient kgClient;

    /**
     * Initialise a new StackInteractor instance.
     * 
     * @param endpoints Pool of parsed endpoints to add to.
     */
    public StackInteractor(List<StackEndpoint> endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * Sets the remote store client used to connect to the KG.
     * 
     * @param kgClient KG connection client.
     */
    public void setKgClient(RemoteStoreClient kgClient) {
        this.kgClient = kgClient;
    }

    /**
     * Uses the TWA Stack client library to determine all Ontop, PostgreSQL, and
     * Blazegraph
     * endpoints within the current stack instance.
     */
    public void discoverEndpoints() throws Exception {
        this.endpoints.addAll(discoverBlazegraph());
        this.endpoints.addAll(discoverOntop());
        this.endpoints.addAll(discoverPostgres());
    }

    /**
     * Determines available Ontop endpoints by querying the "kb" namespace in
     * Blazegraph.
     * 
     * @returns List of available Ontop endpoints.
     */
    private List<StackEndpoint> discoverOntop() {
        List<StackEndpoint> ontopEndpoints = new ArrayList<>();

        // If kb blazegraph endpoint is available and kgClient is set, query it for
        // Ontop endpoints
        if (KB_BLAZEGRAPH_ENDPOINT != null && kgClient != null) {
            try {
                // Read the SPARQL query from file
                String queryString = readSparqlQuery("/app/queries/ontop-query.sparql");

                // Execute the query using RemoteStoreClient
                kgClient.setQueryEndpoint(KB_BLAZEGRAPH_ENDPOINT.url());
                JSONArray queryResult = kgClient.executeQuery(queryString);

                // Extract ontop endpoints from the query result
                for (int i = 0; i < queryResult.length(); i++) {
                    JSONObject binding = queryResult.getJSONObject(i);
                    Object ontopUrl = findField("ontop_url", binding);
                    
                    if (ontopUrl != null) {
                        ontopEndpoints.add(new StackEndpoint(
                                ontopUrl.toString(),
                                null,
                                null,
                                StackEndpointType.ONTOP));
                        LOGGER.info("Have discovered an Ontop endpoint from Blazegraph: {}", ontopUrl);
                    }
                }
            } catch (Exception exception) {
                LOGGER.warn("Could not query Blazegraph for Ontop endpoints, falling back to default discovery",
                        exception);
                // Fall back to default Ontop config discovery
                ontopEndpoints.addAll(discoverOntopDefault());
            }
        } else {
            // KB Blazegraph endpoint or kgClient not available, use default discovery
            ontopEndpoints.addAll(discoverOntopDefault());
        }

        return ontopEndpoints;
    }

    /**
     * Fallback method to discover Ontop endpoints from configuration.
     * 
     * @returns List of available Ontop endpoints.
     */
    private List<StackEndpoint> discoverOntopDefault() {
        List<StackEndpoint> ontopEndpoints = new ArrayList<>();

        OntopEndpointConfig ontopConfig = readEndpointConfig("ontop", OntopEndpointConfig.class);
        ontopEndpoints.add(new StackEndpoint(
                ontopConfig.getUrl(),
                null,
                null,
                StackEndpointType.ONTOP));

        LOGGER.info("Have discovered a local Ontop endpoint: {}", ontopConfig.getUrl());
        return ontopEndpoints;
    }

    /**
     * Reads a SPARQL query from a file.
     * 
     * @param filePath Path to the SPARQL file.
     * @return The SPARQL query as a string.
     * @throws IOException If the file cannot be read.
     */
    private String readSparqlQuery(String filePath) throws IOException {
        return new String(Files.readAllBytes(Paths.get(filePath)));
    }

    /**
     * Determines available PostgreSQL endpoints.
     * 
     * @returns List of available PostgreSQL endpoints.
     */
    private List<StackEndpoint> discoverPostgres() {
        List<StackEndpoint> postgresEndpoints = new ArrayList<>();

        RDB_CONFIG = readEndpointConfig("postgis", PostGISEndpointConfig.class);
        postgresEndpoints.add(new StackEndpoint(
                RDB_CONFIG.getJdbcDriverURL(),
                RDB_CONFIG.getUsername(),
                RDB_CONFIG.getPassword(),
                StackEndpointType.POSTGRES));

        LOGGER.info("Have discovered a local Ontop endpoint: {}", RDB_CONFIG.getJdbcDriverURL());
        return postgresEndpoints;
    }

    /**
     * Determines available Blazegraph endpoints.
     * Specifically finds and caches the "kb" namespace endpoint.
     * 
     * @returns List of available Blazegraph endpoints.
     * @throws Exception if the "kb" namespace endpoint is not found.
     */
    private List<StackEndpoint> discoverBlazegraph() throws Exception {
        // Use the client library to get the root URL of Blazezgraph
        BlazegraphEndpointConfig blazeConfig = readEndpointConfig("blazegraph", BlazegraphEndpointConfig.class);

        // Rather than asking the stack client library, ask Blazegraph itself to
        // tell use the URL endpoint for each of its namespaces.
        NamespaceGetter getter = new NamespaceGetter(
                blazeConfig.getServiceUrl(),
                blazeConfig.getUsername(),
                blazeConfig.getPassword());

        // Run logic to query for namespaces
        try {
            List<StackEndpoint> allEndpoints = getter.discoverEndpoints();

            // Find and cache the "kb" namespace endpoint
            KB_BLAZEGRAPH_ENDPOINT = allEndpoints.stream()
                    .filter(ep -> ep.url().contains("/kb"))
                    .findFirst()
                    .orElse(null);

            if (KB_BLAZEGRAPH_ENDPOINT == null) {
                throw new Exception("Could not find 'kb' namespace endpoint in Blazegraph!");
            }

            LOGGER.info("Have discovered the kb namespace Blazegraph endpoint: {}", KB_BLAZEGRAPH_ENDPOINT.url());
            return allEndpoints;
        } catch (Exception exception) {
            LOGGER.error("Could not contact Blazegraph to determine namespace URLs!", exception);
            throw exception;
        }
    }

    /**
     * Given a database name, this generates and returns the correct URL for it.
     * 
     * @param dbName database name
     */
    public static String generatePostgresURL(String dbName) {
        try {
            return RDB_CONFIG.getJdbcURL(dbName);
        } catch (RuntimeException exception) {
            // Probably not running within a stack
            return "";
        }
    }

    /**
     * Find the JSON field for the input key in any case.
     * 
     * @param key JSON key.
     * @param entry JSONObject to search within.
     * 
     * @return Resulting value (or null).
     */
    private static Object findField(String key, JSONObject entry) {
        if (entry.has(key))
            return entry.get(key);
        if (entry.has(key.toLowerCase()))
            return entry.get(key.toLowerCase());
        if (entry.has(key.toUpperCase()))
            return entry.get(key.toUpperCase());

        return null;
    }

}
// End of class.