package com.cmclinnovations.featureinfo.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import uk.ac.cam.cares.jps.base.query.RemoteStoreClient;

/**
 * This class handles storing configuration details and details on
 * endpoints available within the TWA stack.
 */
public final class ConfigStore {
    
    /**
     * Logger for reporting info/errors.
     */
    private static final Logger LOGGER = LogManager.getLogger(ConfigStore.class);

    /**
     * Name of the environment variable containing config file location.
     */
    private static final String VARIABLE = "FIA_CONFIG_FILE";

    /**
     * List of configuration entries.
     */
    private final List<ConfigEntry> configEntries = new ArrayList<>();

    /**
     * List of available stack endpoints.
     */
    private final List<StackEndpoint> stackEndpoints = new ArrayList<>();

    /**
     * Cached StackInteractor instance.
     */
    private StackInteractor stackInteractor;

    /**
     * Cached location of configuration file.
     */
    private final Path configurationFile;

    /**
     * Initialise a new ConfigStore using the default config file location.
     */
    public ConfigStore() {
        this.configurationFile = Paths.get(getConfigLocation());
    }

    /**
     * Initialise a new ConfigStore using the input config file location.
     * 
     * @param configurationFile absolute location of configuration file.
     */
    public ConfigStore(String configurationFile) {
        this.configurationFile = Paths.get(configurationFile);
    }

    /**
     * Returns the directory containing the configuration file.
     * 
     * @return directory containing the configuration file.
     */
    public Path getConfigurationDirectory() {
        if(this.configurationFile != null) {
            return this.configurationFile.getParent();
        }
        return null;
    }

    /**
     * Returns the loaded configuration entries.
     * 
     * @return configuration entries.
     */
    public List<ConfigEntry> getConfigEntries() {
        return this.configEntries;
    }

    /**
     * Returns the configuration entry with the input ID.
     * 
     * @param id identifier to match.
     * 
     * @return matching entry (or null).
     */
    public ConfigEntry getConfigWithID(String id) {
        return this.configEntries.stream()
        .filter(entry -> entry.getID().equalsIgnoreCase(id))
        .findFirst()
        .orElse(null);
    }

    /**
     * Returns the configuration entry with the class IRI.
     * 
     * @param classIRI IRI to match
     * 
     * @return matching entry (or null).
     */
    public ConfigEntry getConfigWithClass(String classIRI) {
        return this.configEntries.stream()
            .filter(entry -> entry.getClassIRI().equalsIgnoreCase(classIRI))
            .findFirst()
            .orElse(null);
    }

    /**
     * Returns a list of all available stack endpoints.
     * 
     * @return stack endpoints.
     */
    public List<StackEndpoint> getStackEndpoints() {
        if(this.stackEndpoints.isEmpty()) {
            LOGGER.warn("Stack endpoints list is empty - has discoverEndpoints() been called?");
        }
        return this.stackEndpoints;
    }

    /**
     * Returns a filtered list of stack endpoints only of the input type.
     * 
     * @param type stack endpoint type.
     * 
     * @return endpoints of the input type.
     */
    public List<StackEndpoint> getStackEndpoints(StackEndpointType type) {
        if(this.stackEndpoints.isEmpty()) {
            LOGGER.warn("Stack endpoints list is empty - has discoverEndpoints() been called?");
        }
        return this.stackEndpoints.stream()
             .filter(endpoint -> endpoint.type().equals(type))
             .collect(Collectors.toList());
    }

    /**
     * Loads the configuration file details and scans for available
     * TWA Stack endpoints.
     */
    public void loadDetails(RemoteStoreClient kgClient) throws Exception {
            // Get configuration entries
        this.configEntries.clear();
        ConfigReader reader = new ConfigReader(this.configEntries);
        reader.parseConfig(this.configurationFile);
        LOGGER.info("Have parsed a total of {} configuration entries.", this.configEntries.size());

        // Get stack endpoints
    
        this.stackEndpoints.clear();
        this.stackInteractor = new StackInteractor(this.stackEndpoints);
        this.stackInteractor.setKgClient(kgClient);
        try {
            this.stackInteractor.discoverEndpoints();
            LOGGER.info("Have discovered a total of {} stack endpoints.",
                    this.stackInteractor.getEndpoints().size());
        } catch (Exception exception) {
            LOGGER.error("Could not discover stack endpoints!", exception);
        }
    }

    /**
     * Gets the value of the environment variable that should hold the
     * location of the configuration file.
     */
    private String getConfigLocation() throws IllegalStateException {
        String location = System.getenv(VARIABLE);
        if(location == null || location.isBlank()) {
            throw new IllegalStateException("Cannot find the required '" + VARIABLE + "' environment variable!");
        }
        return location;
    }

    /**
     * Returns the cached StackInteractor instance.
     * 
     * @return StackInteractor instance (or null if not initialized).
     */
    public StackInteractor getStackInteractor() {
        return this.stackInteractor;
    }
    

}
// End of class.