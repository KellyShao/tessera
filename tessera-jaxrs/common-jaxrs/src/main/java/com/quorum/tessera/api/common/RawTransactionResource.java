package com.quorum.tessera.api.common;

import com.quorum.tessera.api.StoreRawRequest;
import com.quorum.tessera.api.StoreRawResponse;
import com.quorum.tessera.core.api.ServiceFactory;
import com.quorum.tessera.encryption.PublicKey;
import com.quorum.tessera.transaction.TransactionManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.tags.Tags;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import java.util.Objects;

import static com.quorum.tessera.version.MultiTenancyVersion.MIME_TYPE_JSON_2_1;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

/** Provides endpoints for dealing with raw transactions */
@Tags({@Tag(name = "quorum-to-tessera"), @Tag(name = "third-party")})
@Path("/")
public class RawTransactionResource {

    public static final String ENDPOINT_STORE_RAW = "storeraw";

    private final TransactionManager transactionManager;

    public RawTransactionResource() {
        this(ServiceFactory.create().transactionManager());
    }

    public RawTransactionResource(final TransactionManager transactionManager) {
        this.transactionManager = Objects.requireNonNull(transactionManager);
    }

    @Operation(
            summary = "/storeraw",
            operationId = "encryptAndStore",
            description = "encrypts a payload and stores result in the \"raw\" database")
    @ApiResponse(
            responseCode = "200",
            description = "hash of encrypted payload",
            content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = StoreRawResponse.class)))
    @ApiResponse(responseCode = "404", description = "'from' key in request body not found")
    @POST
    @Path(ENDPOINT_STORE_RAW)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response store(
            @RequestBody(required = true, content = @Content(schema = @Schema(implementation = StoreRawRequest.class)))
                    @NotNull
                    @Valid
                    final StoreRawRequest request) {
        final StoreRawResponse storeRawResponse = this.forwardRequest(request);
        return Response.ok().type(APPLICATION_JSON).entity(storeRawResponse).build();
    }

    @Operation(
            summary = "/storeraw",
            operationId = "encryptAndStore",
            description = "encrypts a payload and stores result in the \"raw\" database")
    @ApiResponse(
            responseCode = "200",
            description = "hash of encrypted payload",
            content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = StoreRawResponse.class)))
    @ApiResponse(responseCode = "404", description = "'from' key in request body not found")
    @POST
    @Path(ENDPOINT_STORE_RAW)
    @Consumes(MIME_TYPE_JSON_2_1)
    @Produces(MIME_TYPE_JSON_2_1)
    public Response storeVersion21(
            @RequestBody(required = true, content = @Content(schema = @Schema(implementation = StoreRawRequest.class)))
                    @NotNull
                    @Valid
                    final StoreRawRequest request) {
        final StoreRawResponse storeRawResponse = this.forwardRequest(request);
        return Response.ok().type(MIME_TYPE_JSON_2_1).entity(storeRawResponse).build();
    }

    private StoreRawResponse forwardRequest(final StoreRawRequest request) {
        final PublicKey sender = request.getFrom().map(PublicKey::from).orElseGet(transactionManager::defaultPublicKey);

        final com.quorum.tessera.transaction.StoreRawRequest storeRawRequest =
                com.quorum.tessera.transaction.StoreRawRequest.Builder.create()
                        .withSender(sender)
                        .withPayload(request.getPayload())
                        .build();

        final com.quorum.tessera.transaction.StoreRawResponse response = transactionManager.store(storeRawRequest);

        final StoreRawResponse storeRawResponse = new StoreRawResponse();
        storeRawResponse.setKey(response.getHash().getHashBytes());

        return storeRawResponse;
    }
}
