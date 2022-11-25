# Feature Info Agent

This Feature Info Agent (FIA) acts as a single access point for [DTVF Visualisations](https://github.com/cambridge-cares/TheWorldAvatar/wiki/Digital-Twin-Visualisations) to query for both meta and timeseries data of an individual feature (i.e. a single geographical location) before display within the visualisation's side panel.

## Overview

The FIA is a relatiely simple HTTP Agent built using the JPS Base Lib's agent framework. When a new request is received, the internal process proceeds as follows:

1. Check if the configuration has been loaded.
   1. If not, then load it.
   2. Dynamically discover the Ontop endpoint.
   3. Dynamically discover the PostGreSQL endpoint.
   4. Dynamically discover all endpoints provided by Blazegraph.
2. Check the incoming route.
   1. Throw error if an unknown route.
3. If `/status` route:
   1. Return current status of the agent.
4. If `/get` route:
   1. Check validity of incoming request.
      1. If malformed, send a BAD_REQUEST response.
      2. Determine the class representing the IRI.
         1. If query successful but no class returned, send a NO_CONTENT response.
      3. Look up corresponding meta query for that class.
         1. If none found, send a NO_CONTENT response.
      4. Run query to get metadata.
      5. Parse resulting metadata into approved DTVF format.
      6. Look up corresponding timeseries query for that class.
         1. If none found, log warning but continue.
         2. If found, run query.
            1. If no data found, quietly continue.
      7. Build and return final JSON object.

It's also worth noting that in the current version of the FIA, any queries to the knowledge graphs are sent to all discovered namespace endpoints via federation. Whilst this will marginally increase processing times, these queries should be pretty quick, and shouldn't be triggered too often, so the risk of delay is hopefully less than the benefit of not having to specify the endpoint beforehand.

## Restrictions

At the time of writing, the FIA has a few restrictions that all users should be aware of. These are as follows:

- The FIA can only be run within a [stack](https://github.com/cambridge-cares/TheWorldAvatar/tree/main/Deploy/stacks/dynamic/stack-manager).
- The FIA can only report on metadata and timeseries that are contained within the same stack.
- The FIA can only return timeseries data on series that use the Instant class.
- The FIA can only return timeseries data from a single PostGreSQL database.
- The FIA cannot handle large, intensive queries (i.e. anything that takes more than a minute to return).

## Requirements

For the FIA to function, a number of configuration steps need to take place before deployment. Additionally, incoming HTTP requests to the agent must meet a set format. These are detailed in the subsections below. It is also neccessary for users to have good knowledge of Docker, JSON, and to be familiar with management of the Stack system.

### Configuration

Follow the below configuration steps within the local `queries` directory.

- Create a JSON configuration file named `fia-config.json`.
  - This configuration file should be a JSON object containing the following parameters:
    - `database_name`: This is a **required** string parameter. It should match the PostGreSQL database name that contains your timeseries data.
    - `queries`: This is a **required** array of objects defining a mapping between class names and the names of files containing pre-written SPARQL queries. Each object needs to contain the following string parameters:
      -  `class`: Full IRI of the class.
      -  `metaFile`: Name of the file (inc. extension) that contains the query to run when gathering metadata.
      -  `timeFile`: Name of the file (inc. extension) that contains the query to run when gathering timeseries measurement details.
    - `hours`: This is an optional integer parameter that defaults to 24. When set, timeseries data from the last N hours will be pulled (or all data if the value is set to below 0).
  - Add the aforementioned metadata and timeseries query files.

An example configuration file is provided within the `queries` directory.

#### Query Restrictions

To properly parse the metadata and timeseries queries, the agent requires the results from queries to fulfill a set format. For each type of query a number of placeholder tokens can be added that will be populated by the agent just before execution. These are:

- `[IRI]`: The IRI of the feature in the request will be injected
- `[ONTOP]`: The URL of the Ontop service within the stack will be injected

Queries for metadata should not concern themselves with data relating to timeseries (that can be handled within the timeseries query). Queries here need to return a table with two (or optionally three) columns. The first column should be named `Property` and contains the name of the parameter we're reporting, the second should be `Value` and contain the value. The optional third column is `Unit`. Any other colums will be ignored.


<p align="center">
    <img src="meta-query-example.jpg" alt="Example result of a metadata query" width="50%"/>
</p>

Queries for timeseries data need to return the measurement/forecast IRIs (that will be used to grab the actual values from PostGreSQL), as well as parameters associated with each measurement/forecast. Required columns are `Measurement` (or `Forecast`) containing the IRI, `Name` containing a user facing name for this entry, and `Unit` containing the unit (which can be blank). In this case, any other columns reported by the query **will** be picked up and passed back to the visualisation as regular key-value properties.


<p align="center">
    <img src="time-query-example.jpg" alt="Example result of a timeseries query" width="75%"/>
</p>

### Requests

All incoming requests should use the `/get` route, containing a `query` parameter that has a JSON body (compatible with the agent framework in the JPS Base Lib), which in turn contains a single `iri` parameter. In this version of the agent, **no** other parameters (e.g. `endpoint`, `namespace`) are required.

## Deployment

The Docker image for this agent should be automatically built and pushed by GitHub whenever a pull request to the main branch is approved and merged. However, it is worth noting that the user that triggers this will require an active GitHub token that has permissions to push packages to the GitHub registry.

Local building can be carried out using the provided docker-compose files as creating `repo_username.txt` and `repo_password.txt` files within the `credentials` directory.

To build the Agent image and deploy it to the spun up stack, please run the following commands from the FeatureInfoAgent directory wherever the stack is running (i.e. potentially on the remote VM):

# Build the agent image
bash ./stack.sh build
# Deploy the agent
bash ./stack.sh start <STACK_NAME>

After deploying the agent, the NGINX routing configuration of your stack may need to be adjusted to ensure the agent is accessible via the `/feature-info-agent` route.

It is worth noting that the docker compose setup for this agent creates a bind mount between the `queries` directory on the host machine, and the `/app/queries` directory within the container. This means that simply adding your configuration and query files to the former before running the container should automatically make them available to the agent.
