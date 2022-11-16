package com.cmclinnovations.featureinfo.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.cmclinnovations.stack.clients.docker.ContainerClient;
import com.cmclinnovations.stack.clients.ontop.OntopEndpointConfig;
import com.cmclinnovations.stack.clients.postgis.PostGISEndpointConfig;

/**
 * Handles reading and storing the configuration settings.
 */
public class ConfigStore {
 
    /**
     * Logger for reporting info/errors.
     */
    private static final Logger LOGGER = LogManager.getLogger(ConfigStore.class);

    /**
     * Name of the environment variable containing config file location.
     */
    protected static final String VARIABLE = "FIA_CONFIG_FILE";

    /**
     * Map of class names to metadata query filenames.
     */
    protected final Map<String, String> metaQueries = new HashMap<>();

    /**
     * Map of class names to timeseries query filenames.
     */
    protected final Map<String, String> timeQueries = new HashMap<>();

    /**
     * Any other settings specified in the config file.
     */
    protected final Map<String, Object> otherSettings = new HashMap<>();
    
    /**
     * Set of endpoint definitions.
     */
    protected final List<ConfigEndpoint> endpoints = new ArrayList<>();

    /**
     * Has this config been loaded/initialised?
     */
    private boolean isReady;

    /**
     * Initialise a new ConfigReader instance.
     */
    public ConfigStore() {
        // Empty
    }

    /**
     * Returns the initialisation state of this config object.
     * 
     * @returns initialisation state
     */
    public boolean isReady() {
        return this.isReady;
    }

    /**
     * Returns the value associated with the input key within the
     * otherSettings map.
     * 
     * @param key property name.
     * 
     * @return property value (or null).
     */
    public Object getSetting(String key) {
        return this.otherSettings.get(key);
    }

    /**
     * Loads the configuration file from the environment variable 
     * and initialises stack configuration.
     * 
     * @throws Exception
     */
    public void load() throws Exception {
        // Load config file
        this.loadFile();

        // Determine stack links
        try {
            determineOntop();
        } catch(RuntimeException exception ) {
            LOGGER.error("Could not dynamically determine Ontop endpoint, this is only permittable during unit tests!");
        }

        try {
            determinePostgres();
        } catch(RuntimeException exception ) {
            LOGGER.error("Could not dynamically determine Postgres endpoint, this is only permittable during unit tests!");
        }

        try {
            determineBlazegraph();
        } catch(RuntimeException exception ) {
            LOGGER.error("Could not dynamically determine any Blazegraph endpoints, this is only permittable during unit tests!");
        }

        // All loaded
        isReady = true;
    }

    /**
     * 
     * @param endpoint
     */
    public void addEndpoint(ConfigEndpoint endpoint) {
        this.endpoints.add(endpoint);
    }

    /**
     * Returns the all known Blazegraph endpoints.
     * 
     * @return endpoints
     */
    public List<ConfigEndpoint> getBlazegraphEndpoints() {
        return endpoints.stream()
            .filter(end -> end.type() == EndpointType.BLAZEGRAPH)
            .toList();
    }

    /**
     * Returns the endpoint object for the ONTOP service.
     * 
     * @return ONTOP endpoint object.
     */
    public Optional<ConfigEndpoint> getOntopEndpoint() {
        return endpoints.stream()
            .filter(end -> end.type() == EndpointType.ONTOP)
            .findFirst();
    }

    /**
     * Returns the endpoint for the POSTGRES service.
     * 
     * @return POSTGRES endpoint object.
     */
    public Optional<ConfigEndpoint> getPostgresEndpoint() {
        return endpoints.stream()
            .filter(end -> end.type() == EndpointType.POSTGRES)
            .findFirst();
    }

    /**
     * If present, returns the raw metadata query linked to the input class.
     * 
     * @param clazz fully qualified class name.
     * 
     * @return sparql content (or null if not linked).
     */
    public String getMetaQuery(String clazz) throws IOException {
        if(metaQueries.containsKey(clazz)) {
            // File name relative to config file location
            String fileName = metaQueries.get(clazz);

            // Try as an absolute path first
            Path filePath = Paths.get(fileName);

            if(!Files.exists(filePath) && this.getConfigLocation() != null) {
                // Try as an a relative path to the config file
                Path configFile = Paths.get(this.getConfigLocation());
                filePath = configFile.getParent().resolve(fileName);
            }

            return Files.readString(filePath);
        }
        return null;
    }

    /**
     * If present, returns the raw timeseries query linked to the input class.
     * 
     * @param clazz fully qualified class name.
     * 
     * @return sparql content (or null if not linked).
     */
    public String getTimeQuery(String clazz) throws IOException {
        if(timeQueries.containsKey(clazz)) {
            // File name relative to config file location
            String fileName = timeQueries.get(clazz);
            
            // Try as an absolute path first
            Path filePath = Paths.get(fileName);
            
            if(!Files.exists(filePath) && this.getConfigLocation() != null) {
                // Try as an a relative path to the config file
                Path configFile = Paths.get(this.getConfigLocation());
                filePath = configFile.getParent().resolve(fileName);
            }

            return Files.readString(filePath);
        }
        return null;
    }

    /**
     * Registers a metadata query file for the input class name.
     * 
     * @param clazz class name.
     * @param queryFile location of query file.
     */
    public void addMetaQueryForClass(String clazz, String queryFile) {
        metaQueries.put(clazz, queryFile);
    }

    /**
     * Registers a timeseries file for the input class name.
     * 
     * @param clazz class name.
     * @param queryFile location of query file.
     */
    public void addTimeQueryForClass(String clazz, String queryFile) {
        timeQueries.put(clazz, queryFile);
    }

    /**
     * If running within a stack, use the stack client library to 
     * determine, and store, the location of the ONTOP container.
     */
    private void determineOntop() {
        ContainerClient client = new ContainerClient();
        OntopEndpointConfig ontopConfig = client.readEndpointConfig("ontop", OntopEndpointConfig.class);
        
        ConfigEndpoint endpoint = new ConfigEndpoint(
            "ONTOP",
            ontopConfig.getUrl(),
            ontopConfig.getUsername(),
            ontopConfig.getPassword(),
            EndpointType.ONTOP
        );
        endpoints.add(endpoint);

        String url = endpoint.url();
        LOGGER.info("Have determined Ontop endpoint as: {}", url);
    }

    /**
     * If running within a stack, use the stack client library to 
     * determine, and store, the location of the POSTGRES container.
     */
    private void determinePostgres() {
        ContainerClient client = new ContainerClient();
        PostGISEndpointConfig postConfig = client.readEndpointConfig("postgis", PostGISEndpointConfig.class);
        
        ConfigEndpoint endpoint = new ConfigEndpoint(
            "POSTGRES",
            postConfig.getJdbcURL(getSetting("database_name").toString()),
            postConfig.getUsername(),
            postConfig.getPassword(),
            EndpointType.POSTGRES
        );
        endpoints.add(endpoint);

        String url = endpoint.url();
        LOGGER.info("Have determined PostgreSQL endpoint as: {}", url);
    }

    /**
     * Dynamically determine the namespace endpoints provided by Blazegraph.
     */
    private void determineBlazegraph() throws Exception {
        NamespaceGetter getter = new NamespaceGetter(null);

        // Dynamically determine namespaces
        Map<String, String> namespaces = getter.listNamespaces();
        
        // Store as endpoint objects
        for(Map.Entry<String, String> entry : namespaces.entrySet()) {
            ConfigEndpoint endpoint = new ConfigEndpoint(
                entry.getKey(),
                entry.getValue(),
                getter.getUsername(),
                getter.getPassword(),
                EndpointType.BLAZEGRAPH
            );

            endpoints.add(endpoint);
            String url = endpoint.url();
            LOGGER.info("Have found a Blazegraph endpoint at: {}", url);
        }
    }

    /**
     * Attempts to load the configuration file at the location specified by the 
     * environment variable.
     * 
     * @throws Exception if config file cannot be read
     */
    public void loadFile() throws IOException {
        String configLocation = this.getConfigLocation();
        if(configLocation != null) this.loadFile(Paths.get(configLocation));
    }

    /**
     * Attempts to load the configuration file at the location specified.
     * 
     * @param configFile location of config file
     * @throws Exception if config file cannot be read
     */
    public void loadFile(Path configFile) throws IOException {
        if(configFile == null) return;

        // Check the file is okay
        if(!Files.exists(configFile)) {
            throw new IOException("Could not find configuration file at: " + configFile);
        }
        if(!Files.isRegularFile(configFile)) {
            throw new IOException("Configuration file does not appear to be regular file.");
        }
        if(!Files.isReadable(configFile)) {
            throw new IOException("Configuration file exists, but is not readable.");
        }

        // Read and parse as JSON
        String content = Files.readString(configFile);
        this.loadFile(content);
    }

    /**
     * Attempts to load the configuration file at the input location.
     * 
     * @param content raw content of config file
     * 
     * @throws Exception if config file cannot be read
     */
    public void loadFile(String content) {
        JSONObject jsonConfig = new JSONObject(content);

        // Get the definition of query files
        JSONArray queries = jsonConfig.getJSONArray("queries");
        if(queries == null) throw new IllegalStateException("Could not find required 'queries' node in configuration.");

        for(int i = 0; i < queries.length(); i++) {
            JSONObject entry = queries.getJSONObject(i);

            // Load in class to file mapping
            String className = entry.getString("class");
            String metaFile = entry.getString("metaFile");
            String timeFile = entry.getString("timeFile");

            metaQueries.put(className, metaFile);
            timeQueries.put(className, timeFile);
        }

        // Store other settings
        jsonConfig.keySet().forEach(key -> {
            if(!key.equals("queries")) otherSettings.put(key, jsonConfig.get(key));
        });
        
        LOGGER.info("Configuration settings have been loaded into memory.");
    }

    /**
     * Gets the value of the environment variable that should hold the
     * location of the configuration file.
     */
    private String getConfigLocation() throws IllegalStateException {
        String location = System.getenv(VARIABLE);
        if(location == null || location.isBlank()) {
            LOGGER.warn("Cannot find value for the '" + VARIABLE + "' environment variable.");
        }
        return location;
    }
    
}
// End of class.