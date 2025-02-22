/*
 * Copyright 2019, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.graphql;

import com.yahoo.elide.Elide;
import com.yahoo.elide.ElideResponse;
import com.yahoo.elide.core.datastore.DataStoreTransaction;
import com.yahoo.elide.core.exceptions.CustomErrorException;
import com.yahoo.elide.core.exceptions.ErrorObjects;
import com.yahoo.elide.core.exceptions.ForbiddenAccessException;
import com.yahoo.elide.core.exceptions.HttpStatus;
import com.yahoo.elide.core.exceptions.HttpStatusException;
import com.yahoo.elide.core.exceptions.InvalidEntityBodyException;
import com.yahoo.elide.core.exceptions.TimeoutException;
import com.yahoo.elide.core.exceptions.TransactionException;
import com.yahoo.elide.core.security.User;
import com.yahoo.elide.graphql.parser.GraphQLEntityProjectionMaker;
import com.yahoo.elide.graphql.parser.GraphQLProjectionInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.apache.commons.lang3.tuple.Pair;
import org.owasp.encoder.Encode;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.execution.AsyncSerialExecutionStrategy;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

/**
 * Entry point for REST endpoints to execute GraphQL queries.
 */
@Slf4j
public class QueryRunner {
    private final Elide elide;
    private GraphQL api;
    private String apiVersion;

    private static final String QUERY = "query";
    private static final String OPERATION_NAME = "operationName";
    private static final String VARIABLES = "variables";
    private static final String MUTATION = "mutation";

    /**
     * Builds a new query runner.
     * @param elide The singular elide instance for this service.
     */
    public QueryRunner(Elide elide, String apiVersion) {
        this.elide = elide;
        this.apiVersion = apiVersion;

        NonEntityDictionary nonEntityDictionary = new NonEntityDictionary();
        PersistentResourceFetcher fetcher = new PersistentResourceFetcher(nonEntityDictionary);
        ModelBuilder builder = new ModelBuilder(elide.getElideSettings().getDictionary(),
                nonEntityDictionary, fetcher, apiVersion);

        api = GraphQL.newGraphQL(builder.build())
                .queryExecutionStrategy(new AsyncSerialExecutionStrategy())
                .build();

        // TODO - add serializers to allow for custom handling of ExecutionResult and GraphQLError objects
        GraphQLErrorSerializer errorSerializer = new GraphQLErrorSerializer();
        SimpleModule module = new SimpleModule("ExecutionResultSerializer", Version.unknownVersion());
        module.addSerializer(ExecutionResult.class, new ExecutionResultSerializer(errorSerializer));
        module.addSerializer(GraphQLError.class, errorSerializer);
        elide.getElideSettings().getMapper().getObjectMapper().registerModule(module);
    }

    /**
     * Execute a GraphQL query and return the response.
     * @param baseUrlEndPoint base URL with prefix endpoint
     * @param graphQLDocument The graphQL document (wrapped in JSON payload).
     * @param user The user who issued the query.
     * @return The response.
     */
    public ElideResponse run(String baseUrlEndPoint, String graphQLDocument, User user) {
        return run(baseUrlEndPoint, graphQLDocument, user, UUID.randomUUID());
    }

    /**
     * Check if a query string is mutation.
     * @param query The graphQL Query to verify.
     * @return is a mutation.
     */
    public static boolean isMutation(String query) {
        return query != null && query.trim().startsWith(MUTATION);
    }

    /**
     * Extracts the top level JsonNode from GraphQL document.
     * @param mapper ObjectMapper instance.
     * @param graphQLDocument The graphQL document (wrapped in JSON payload).
     * @return The JsonNode after parsing graphQLDocument.
     * @throws IOException IOException
     */
    public static JsonNode getTopLevelNode(ObjectMapper mapper, String graphQLDocument) throws IOException {
        return mapper.readTree(graphQLDocument);
    }

    /**
     * Execute a GraphQL query and return the response.
     * @param graphQLDocument The graphQL document (wrapped in JSON payload).
     * @param user The user who issued the query.
     * @param requestId the Request ID.
     * @return The response.
     */
    public ElideResponse run(String baseUrlEndPoint, String graphQLDocument, User user, UUID requestId) {
        return run(baseUrlEndPoint, graphQLDocument, user, requestId, null);
    }

    /**
     * Execute a GraphQL query and return the response.
     * @param graphQLDocument The graphQL document (wrapped in JSON payload).
     * @param user The user who issued the query.
     * @param requestId the Request ID.
     * @return The response.
     */
    public ElideResponse run(String baseUrlEndPoint, String graphQLDocument, User user, UUID requestId,
                             Map<String, List<String>> requestHeaders) {
        ObjectMapper mapper = elide.getMapper().getObjectMapper();

        JsonNode topLevel;

        try {
            topLevel = getTopLevelNode(mapper, graphQLDocument);
        } catch (IOException e) {
            log.debug("Invalid json body provided to GraphQL", e);
            // NOTE: Can't get at isVerbose setting here for hardcoding to false. If necessary, we can refactor
            // so this can be set appropriately.
            return buildErrorResponse(elide, new InvalidEntityBodyException(graphQLDocument), false);
        }

        Function<JsonNode, ElideResponse> executeRequest =
                (node) -> executeGraphQLRequest(baseUrlEndPoint, mapper, user, graphQLDocument, node, requestId,
                                                requestHeaders);

        if (topLevel.isArray()) {
            Iterator<JsonNode> nodeIterator = topLevel.iterator();
            Iterable<JsonNode> nodeIterable = () -> nodeIterator;
            // NOTE: Create a non-parallel stream
            // It's unclear whether or not the expectations of the caller would be that requests are intended
            // to run serially even outside of a single transaction. We should revisit this.
            Stream<JsonNode> nodeStream = StreamSupport.stream(nodeIterable.spliterator(), false);
            ArrayNode result = nodeStream
                    .map(executeRequest)
                    .map(response -> {
                        try {
                            return mapper.readTree(response.getBody());
                        } catch (IOException e) {
                            log.debug("Caught an IO exception while trying to read response body");
                            return JsonNodeFactory.instance.objectNode();
                        }
                    })
                    .reduce(JsonNodeFactory.instance.arrayNode(),
                            (arrayNode, node) -> arrayNode.add(node),
                            (left, right) -> left.addAll(right));
            try {
                return ElideResponse.builder()
                        .responseCode(HttpStatus.SC_OK)
                        .body(mapper.writeValueAsString(result))
                        .build();
            } catch (IOException e) {
                log.error("An unexpected error occurred trying to serialize array response.", e);
                return ElideResponse.builder()
                        .responseCode(HttpStatus.SC_INTERNAL_SERVER_ERROR)
                        .build();
            }
        }

        return executeRequest.apply(topLevel);
    }

    /**
     * Extracts the executable query from Json Node.
     * @param jsonDocument The JsonNode object.
     * @return query to execute.
     */
    public static String extractQuery(JsonNode jsonDocument) {
        return jsonDocument.has(QUERY) ? jsonDocument.get(QUERY).asText() : null;
    }

    /**
     * Extracts the variables for the query from Json Node.
     * @param mapper ObjectMapper instance.
     * @param jsonDocument The JsonNode object.
     * @return variables to pass.
     */
    public static Map<String, Object> extractVariables(ObjectMapper mapper, JsonNode jsonDocument) {
        // get variables from request for constructing entityProjections
        Map<String, Object> variables = new HashMap<>();
        if (jsonDocument.has(VARIABLES) && !jsonDocument.get(VARIABLES).isNull()) {
            variables = mapper.convertValue(jsonDocument.get(VARIABLES), Map.class);
        }

        return variables;
    }

    /**
     * Extracts the operation name from Json Node.
     * @param jsonDocument The JsonNode object.
     * @return variables to pass.
     */
    public static String extractOperation(JsonNode jsonDocument) {
        if (jsonDocument.has(OPERATION_NAME) && !jsonDocument.get(OPERATION_NAME).isNull()) {
            return jsonDocument.get(OPERATION_NAME).asText();
        }

        return null;
    }

    private ElideResponse executeGraphQLRequest(String baseUrlEndPoint, ObjectMapper mapper, User principal,
                                                String graphQLDocument, JsonNode jsonDocument, UUID requestId,
                                                Map<String, List<String>> requestHeaders) {
        boolean isVerbose = false;
        try (DataStoreTransaction tx = elide.getDataStore().beginTransaction()) {
            elide.getTransactionRegistry().addRunningTransaction(requestId, tx);
            if (!jsonDocument.has(QUERY)) {
                return ElideResponse.builder().responseCode(HttpStatus.SC_BAD_REQUEST)
                        .body("A `query` key is required.").build();
            }
            String query = extractQuery(jsonDocument);

            // get variables from request for constructing entityProjections
            Map<String, Object> variables = extractVariables(mapper, jsonDocument);

            //TODO - get API version.
            GraphQLProjectionInfo projectionInfo = new GraphQLEntityProjectionMaker(elide.getElideSettings(), variables,
                    apiVersion).make(query);
            GraphQLRequestScope requestScope = new GraphQLRequestScope(baseUrlEndPoint, tx, principal, apiVersion,
                    elide.getElideSettings(), projectionInfo, requestId, requestHeaders);

            isVerbose = requestScope.getPermissionExecutor().isVerbose();

            // Logging all queries. It is recommended to put any private information that shouldn't be logged into
            // the "variables" section of your query. Variable values are not logged.
            log.info("Processing GraphQL query:\n{}", query);

            ExecutionInput.Builder executionInput = new ExecutionInput.Builder().context(requestScope).query(query);

            String operationName = extractOperation(jsonDocument);

            if (operationName != null) {
                executionInput.operationName(operationName);
            }
            executionInput.variables(variables);

            ExecutionResult result = api.execute(executionInput);

            tx.preCommit(requestScope);
            requestScope.runQueuedPreSecurityTriggers();
            requestScope.getPermissionExecutor().executeCommitChecks();
            if (isMutation(query)) {
                if (!result.getErrors().isEmpty()) {
                    HashMap<String, Object> abortedResponseObject = new HashMap<>();
                    abortedResponseObject.put("errors", result.getErrors());
                    abortedResponseObject.put("data", null);
                    // Do not commit. Throw OK response to process tx.close correctly.
                    throw new WebApplicationException(
                            Response.ok(mapper.writeValueAsString(abortedResponseObject)).build());
                }
                requestScope.saveOrCreateObjects();
            }
            requestScope.runQueuedPreFlushTriggers();
            tx.flush(requestScope);

            requestScope.runQueuedPreCommitTriggers();
            elide.getAuditLogger().commit();
            tx.commit(requestScope);
            requestScope.runQueuedPostCommitTriggers();

            if (log.isTraceEnabled()) {
                requestScope.getPermissionExecutor().logCheckStats();
            }

            return ElideResponse.builder().responseCode(HttpStatus.SC_OK).body(mapper.writeValueAsString(result))
                    .build();
        } catch (JsonProcessingException e) {
            log.debug("Invalid json body provided to GraphQL", e);
            return buildErrorResponse(elide, new InvalidEntityBodyException(graphQLDocument), isVerbose);
        } catch (IOException e) {
            log.error("Uncaught IO Exception by Elide in GraphQL", e);
            return buildErrorResponse(elide, new TransactionException(e), isVerbose);
        } catch (WebApplicationException e) {
            log.debug("WebApplicationException", e);
            String body = e.getResponse().getEntity() != null ? e.getResponse().getEntity().toString() : e.getMessage();
            return ElideResponse.builder().responseCode(e.getResponse().getStatus()).body(body).build();
        } catch (HttpStatusException e) {
            if (e instanceof ForbiddenAccessException) {
                if (log.isDebugEnabled()) {
                    log.debug("{}", ((ForbiddenAccessException) e).getLoggedMessage());
                }
            } else {
                log.debug("Caught HTTP status exception {}", e.getStatus(), e);
            }
            return buildErrorResponse(elide, new HttpStatusException(200, e.getMessage()) {
                @Override
                public int getStatus() {
                    return 200;
                }

                @Override
                public Pair<Integer, JsonNode> getErrorResponse() {
                    return e.getErrorResponse();
                }

                @Override
                public Pair<Integer, JsonNode> getVerboseErrorResponse() {
                    return e.getVerboseErrorResponse();
                }

                @Override
                public String getVerboseMessage() {
                    return e.getVerboseMessage();
                }

                @Override
                public String toString() {
                    return e.toString();
                }
            }, isVerbose);
        } catch (ConstraintViolationException e) {
            log.debug("Constraint violation exception caught", e);
            String message = "Constraint violation";
            final ErrorObjects.ErrorObjectsBuilder errorObjectsBuilder = ErrorObjects.builder();
            for (ConstraintViolation<?> constraintViolation : e.getConstraintViolations()) {
                errorObjectsBuilder.addError()
                        .withDetail(constraintViolation.getMessage());
                final String propertyPathString = constraintViolation.getPropertyPath().toString();
                if (!propertyPathString.isEmpty()) {
                    Map<String, Object> source = new HashMap<>(1);
                    source.put("property", propertyPathString);
                    errorObjectsBuilder.with("source", source);
                }
            }
            return buildErrorResponse(elide,
                    new CustomErrorException(HttpStatus.SC_BAD_REQUEST, message, errorObjectsBuilder.build()),
                    isVerbose
            );
        } catch (Exception | Error e) {
            if (e instanceof InterruptedException) {
                log.debug("Request Thread interrupted.", e);
                return buildErrorResponse(elide, new TimeoutException(e), isVerbose);
            }
            log.error("Unhandled error or exception.", e);
            throw e;
        } finally {
            elide.getTransactionRegistry().removeRunningTransaction(requestId);
            elide.getAuditLogger().clear();
        }
    }
    public static ElideResponse buildErrorResponse(Elide elide, HttpStatusException error, boolean isVerbose) {
        ObjectMapper mapper = elide.getMapper().getObjectMapper();
        JsonNode errorNode;
        if (!(error instanceof CustomErrorException)) {
            // get the error message and optionally encode it
            String errorMessage = isVerbose ? error.getVerboseMessage() : error.getMessage();
            errorMessage = Encode.forHtml(errorMessage);
            ErrorObjects errors = ErrorObjects.builder().addError()
                    .with("message", errorMessage).build();
            errorNode = mapper.convertValue(errors, JsonNode.class);
        } else {
            errorNode = isVerbose
                    ? error.getVerboseErrorResponse().getRight()
                    : error.getErrorResponse().getRight();
        }
        String errorBody;
        try {
            errorBody = mapper.writeValueAsString(errorNode);
        } catch (JsonProcessingException e) {
            errorBody = errorNode.toString();
        }
        return ElideResponse.builder()
                .responseCode(error.getStatus())
                .body(errorBody)
                .build();
    }
}
