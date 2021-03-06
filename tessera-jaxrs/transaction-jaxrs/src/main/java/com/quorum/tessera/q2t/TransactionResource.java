package com.quorum.tessera.q2t;

import com.quorum.tessera.api.*;
import com.quorum.tessera.api.constraint.PrivacyValid;
import com.quorum.tessera.config.constraints.ValidBase64;
import com.quorum.tessera.data.MessageHash;
import com.quorum.tessera.enclave.PrivacyMode;
import com.quorum.tessera.encryption.PublicKey;
import com.quorum.tessera.transaction.TransactionManager;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static javax.ws.rs.core.MediaType.*;

/**
 * Provides endpoints for dealing with transactions, including:
 *
 * <p>- creating new transactions and distributing them - deleting transactions - fetching transactions - resending old
 * transactions
 */
@Tag(name = "quorum-to-tessera")
@Path("/")
public class TransactionResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionResource.class);

    private final TransactionManager transactionManager;

    public TransactionResource(TransactionManager transactionManager) {
        this.transactionManager = Objects.requireNonNull(transactionManager);
    }

    @Operation(
            summary = "/send",
            operationId = "encryptStoreAndSendJson",
            description = "encrypts a payload, stores result in database, and publishes result to recipients")
    @ApiResponse(
            responseCode = "201",
            description = "encrypted payload hash",
            content = @Content(schema = @Schema(implementation = SendResponse.class)))
    @POST
    @Path("send")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response send(@NotNull @Valid @PrivacyValid final SendRequest sendRequest) {

        Base64.Decoder base64Decoder = Base64.getDecoder();

        PublicKey sender =
                Optional.ofNullable(sendRequest.getFrom())
                        .map(base64Decoder::decode)
                        .map(PublicKey::from)
                        .orElseGet(transactionManager::defaultPublicKey);

        final List<PublicKey> recipientList =
                Stream.of(sendRequest)
                        .filter(sr -> Objects.nonNull(sr.getTo()))
                        .flatMap(s -> Stream.of(s.getTo()))
                        .map(base64Decoder::decode)
                        .map(PublicKey::from)
                        .collect(Collectors.toList());

        final Set<MessageHash> affectedTransactions =
                Stream.ofNullable(sendRequest.getAffectedContractTransactions())
                        .flatMap(Arrays::stream)
                        .map(Base64.getDecoder()::decode)
                        .map(MessageHash::new)
                        .collect(Collectors.toSet());

        final byte[] execHash =
                Optional.ofNullable(sendRequest.getExecHash()).map(String::getBytes).orElse(new byte[0]);

        final PrivacyMode privacyMode = PrivacyMode.fromFlag(sendRequest.getPrivacyFlag());

        final com.quorum.tessera.transaction.SendRequest request =
                com.quorum.tessera.transaction.SendRequest.Builder.create()
                        .withRecipients(recipientList)
                        .withSender(sender)
                        .withPayload(sendRequest.getPayload())
                        .withExecHash(execHash)
                        .withPrivacyMode(privacyMode)
                        .withAffectedContractTransactions(affectedTransactions)
                        .build();

        final com.quorum.tessera.transaction.SendResponse response = transactionManager.send(request);

        final String encodedKey =
                Optional.of(response)
                        .map(com.quorum.tessera.transaction.SendResponse::getTransactionHash)
                        .map(MessageHash::getHashBytes)
                        .map(Base64.getEncoder()::encodeToString)
                        .get();

        final SendResponse sendResponse =
                Optional.of(response)
                        .map(com.quorum.tessera.transaction.SendResponse::getTransactionHash)
                        .map(MessageHash::getHashBytes)
                        .map(Base64.getEncoder()::encodeToString)
                        .map(messageHash -> new SendResponse(messageHash, null))
                        .get();

        final URI location =
                UriBuilder.fromPath("transaction").path(URLEncoder.encode(encodedKey, StandardCharsets.UTF_8)).build();

        return Response.status(Status.CREATED).type(APPLICATION_JSON).location(location).entity(sendResponse).build();
    }

    @Operation(
            operationId = "sendStored",
            summary = "/sendsignedtx",
            description =
                    "re-wraps a pre-stored & pre-encrypted payload, stores result in database, and publishes result to recipients",
            requestBody =
                    @RequestBody(
                            content = {
                                @Content(
                                        mediaType = APPLICATION_JSON,
                                        schema = @Schema(implementation = SendSignedRequest.class)),
                                @Content(
                                        mediaType = APPLICATION_OCTET_STREAM,
                                        array =
                                                @ArraySchema(
                                                        schema =
                                                                @Schema(
                                                                        description = "hash of pre-stored payload",
                                                                        type = "string",
                                                                        format = "base64")))
                            }))
    @ApiResponse(
            responseCode = "200",
            description = "hash of rewrapped payload (for application/octet-stream requests)",
            content =
                    @Content(
                            schema =
                                    @Schema(
                                            description = "hash of rewrapped payload",
                                            type = "string",
                                            format = "base64")))
    @ApiResponse(
            responseCode = "201",
            description = "hash of rewrapped payload (for application/json requests)",
            content =
                    @Content(
                            mediaType = APPLICATION_JSON,
                            schema =
                                    @Schema(
                                            implementation = SendResponse.class,
                                            description = "hash of rewrapped payload")))
    @POST
    @Path("sendsignedtx")
    @Consumes(APPLICATION_OCTET_STREAM)
    @Produces(TEXT_PLAIN)
    public Response sendSignedTransactionStandard(
            @Parameter(
                            description =
                                    "comma-separated list of recipient public keys (for application/octet-stream requests)",
                            schema = @Schema(format = "base64"))
                    @HeaderParam("c11n-to")
                    final String recipientKeys,
            @Valid @NotNull @Size(min = 1) final byte[] signedTransaction) {

        final List<PublicKey> recipients =
                Stream.ofNullable(recipientKeys)
                        .filter(s -> !Objects.equals("", s))
                        .map(v -> v.split(","))
                        .flatMap(Arrays::stream)
                        .map(Base64.getDecoder()::decode)
                        .map(PublicKey::from)
                        .collect(Collectors.toList());

        final com.quorum.tessera.transaction.SendSignedRequest request =
                com.quorum.tessera.transaction.SendSignedRequest.Builder.create()
                        .withRecipients(recipients)
                        .withSignedData(signedTransaction)
                        .withPrivacyMode(PrivacyMode.STANDARD_PRIVATE)
                        .withAffectedContractTransactions(Collections.emptySet())
                        .withExecHash(new byte[0])
                        .build();

        final com.quorum.tessera.transaction.SendResponse response = transactionManager.sendSignedTransaction(request);

        final String encodedTransactionHash =
                Base64.getEncoder().encodeToString(response.getTransactionHash().getHashBytes());

        LOGGER.debug("Encoded key: {}", encodedTransactionHash);

        URI location =
                UriBuilder.fromPath("transaction")
                        .path(URLEncoder.encode(encodedTransactionHash, StandardCharsets.UTF_8))
                        .build();

        // TODO: Quorum expects only 200 responses. When Quorum can handle a 201, change to CREATED
        return Response.status(Status.OK).entity(encodedTransactionHash).location(location).build();
    }

    // path /sendsignedtx is overloaded (application/octet-stream and application/json) annotations cannot handle
    // situations like this so hide this operation and document both in the other methods
    @Hidden
    @POST
    @Path("sendsignedtx")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response sendSignedTransactionEnhanced(
            @NotNull @Valid @PrivacyValid final SendSignedRequest sendSignedRequest) {

        final List<PublicKey> recipients =
                Optional.ofNullable(sendSignedRequest.getTo())
                        .map(Arrays::stream)
                        .orElse(Stream.empty())
                        .map(Base64.getDecoder()::decode)
                        .map(PublicKey::from)
                        .collect(Collectors.toList());

        final PrivacyMode privacyMode = PrivacyMode.fromFlag(sendSignedRequest.getPrivacyFlag());

        final Set<MessageHash> affectedTransactions =
                Stream.ofNullable(sendSignedRequest.getAffectedContractTransactions())
                        .flatMap(Arrays::stream)
                        .map(Base64.getDecoder()::decode)
                        .map(MessageHash::new)
                        .collect(Collectors.toSet());

        final byte[] execHash =
                Optional.ofNullable(sendSignedRequest.getExecHash()).map(String::getBytes).orElse(new byte[0]);

        final com.quorum.tessera.transaction.SendSignedRequest request =
                com.quorum.tessera.transaction.SendSignedRequest.Builder.create()
                        .withSignedData(sendSignedRequest.getHash())
                        .withRecipients(recipients)
                        .withPrivacyMode(privacyMode)
                        .withAffectedContractTransactions(affectedTransactions)
                        .withExecHash(execHash)
                        .build();

        final com.quorum.tessera.transaction.SendResponse response = transactionManager.sendSignedTransaction(request);

        final String endcodedTransactionHash =
                Optional.of(response)
                        .map(com.quorum.tessera.transaction.SendResponse::getTransactionHash)
                        .map(MessageHash::getHashBytes)
                        .map(Base64.getEncoder()::encodeToString)
                        .get();

        LOGGER.debug("Encoded key: {}", endcodedTransactionHash);

        URI location =
                UriBuilder.fromPath("transaction")
                        .path(URLEncoder.encode(endcodedTransactionHash, StandardCharsets.UTF_8))
                        .build();

        SendResponse sendResponse = new SendResponse();
        sendResponse.setKey(endcodedTransactionHash);

        return Response.status(Status.CREATED).type(APPLICATION_JSON).location(location).entity(sendResponse).build();
    }

    @Operation(
            summary = "/sendraw",
            operationId = "encryptStoreAndSendOctetStream",
            description = "encrypts a payload, stores result in database, and publishes result to recipients")
    @ApiResponse(
            responseCode = "200",
            description = "encrypted payload hash",
            content =
                    @Content(
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "base64",
                                            description = "encrypted payload hash")))
    @POST
    @Path("sendraw")
    @Consumes(APPLICATION_OCTET_STREAM)
    @Produces(TEXT_PLAIN)
    public Response sendRaw(
            @HeaderParam("c11n-from")
                    @Parameter(
                            description =
                                    "public key identifying the server's key pair that will be used in the encryption; if not set, default used",
                            schema = @Schema(format = "base64"))
                    @Valid
                    @ValidBase64
                    final String sender,
            @HeaderParam("c11n-to")
                    @Parameter(
                            description = "comma-separated list of recipient public keys",
                            schema = @Schema(format = "base64"))
                    final String recipientKeys,
            @Schema(description = "data to be encrypted") @NotNull @Size(min = 1) @Valid final byte[] payload) {

        final PublicKey senderKey =
                Optional.ofNullable(sender)
                        .filter(Predicate.not(String::isEmpty))
                        .map(Base64.getDecoder()::decode)
                        .map(PublicKey::from)
                        .orElseGet(transactionManager::defaultPublicKey);

        final List<PublicKey> recipients =
                Stream.of(recipientKeys)
                        .filter(Objects::nonNull)
                        .filter(s -> !Objects.equals("", s))
                        .map(v -> v.split(","))
                        .flatMap(Arrays::stream)
                        .map(Base64.getDecoder()::decode)
                        .map(PublicKey::from)
                        .collect(Collectors.toList());

        final com.quorum.tessera.transaction.SendRequest request =
                com.quorum.tessera.transaction.SendRequest.Builder.create()
                        .withSender(senderKey)
                        .withRecipients(recipients)
                        .withPayload(payload)
                        .withPrivacyMode(PrivacyMode.STANDARD_PRIVATE)
                        .withAffectedContractTransactions(Collections.emptySet())
                        .withExecHash(new byte[0])
                        .build();

        final com.quorum.tessera.transaction.SendResponse sendResponse = transactionManager.send(request);

        final String encodedTransactionHash =
                Optional.of(sendResponse)
                        .map(com.quorum.tessera.transaction.SendResponse::getTransactionHash)
                        .map(MessageHash::getHashBytes)
                        .map(Base64.getEncoder()::encodeToString)
                        .get();

        LOGGER.debug("Encoded key: {}", encodedTransactionHash);

        URI location =
                UriBuilder.fromPath("transaction")
                        .path(URLEncoder.encode(encodedTransactionHash, StandardCharsets.UTF_8))
                        .build();

        // TODO: Quorum expects only 200 responses. When Quorum can handle a 201, change to CREATED
        return Response.status(Status.OK).entity(encodedTransactionHash).location(location).build();
    }

    @Operation(
            summary = "/transaction/{hash}",
            operationId = "getDecryptedPayloadJsonUrl",
            description = "get payload from database, decrypt, and return")
    @ApiResponse(
            responseCode = "200",
            description = "decrypted payload",
            content = @Content(schema = @Schema(implementation = ReceiveResponse.class)))
    @GET
    @Path("/transaction/{hash}")
    @Produces(APPLICATION_JSON)
    public Response receive(
            @Parameter(
                            description = "hash indicating encrypted payload to retrieve from database",
                            schema = @Schema(format = "base64"))
                    @Valid
                    @ValidBase64
                    @PathParam("hash")
                    final String hash,
            @Parameter(
                            description =
                                    "(optional) public key of recipient of the encrypted payload; used in decryption; if not provided, decryption is attempted with all known recipient keys in turn",
                            schema = @Schema(format = "base64"))
                    @QueryParam("to")
                    final String toStr,
            @Parameter(
                            description =
                                    "(optional) indicates whether the payload is raw; determines which database the payload is retrieved from; possible values\n* true - for pre-stored payloads in the \"raw\" database\n* false (default) - for already sent payloads in \"standard\" database")
                    @Valid
                    @Pattern(flags = Pattern.Flag.CASE_INSENSITIVE, regexp = "^(true|false)$")
                    @QueryParam("isRaw")
                    final String isRaw) {

        Base64.Decoder base64Decoder = Base64.getDecoder();
        final PublicKey recipient =
                Optional.ofNullable(toStr)
                        .filter(Predicate.not(String::isEmpty))
                        .map(base64Decoder::decode)
                        .map(PublicKey::from)
                        .orElse(null);

        final MessageHash transactionHash = Optional.of(hash).map(base64Decoder::decode).map(MessageHash::new).get();

        final com.quorum.tessera.transaction.ReceiveRequest request =
                com.quorum.tessera.transaction.ReceiveRequest.Builder.create()
                        .withRecipient(recipient)
                        .withTransactionHash(transactionHash)
                        .withRaw(Boolean.valueOf(isRaw))
                        .build();

        com.quorum.tessera.transaction.ReceiveResponse response = transactionManager.receive(request);

        final ReceiveResponse receiveResponse = new ReceiveResponse();
        receiveResponse.setPayload(response.getUnencryptedTransactionData());
        receiveResponse.setAffectedContractTransactions(
                response.getAffectedTransactions().stream()
                        .map(MessageHash::getHashBytes)
                        .map(Base64.getEncoder()::encodeToString)
                        .toArray(String[]::new));

        Optional.ofNullable(response.getExecHash()).map(String::new).ifPresent(receiveResponse::setExecHash);

        receiveResponse.setPrivacyFlag(response.getPrivacyMode().getPrivacyFlag());

        return Response.status(Status.OK).type(APPLICATION_JSON).entity(receiveResponse).build();
    }

    @Operation(
            summary = "/receive",
            operationId = "getDecryptedPayloadJson",
            description = "get payload from database, decrypt, and return")
    @ApiResponse(
            responseCode = "200",
            description = "decrypted payload",
            content = @Content(schema = @Schema(implementation = ReceiveResponse.class)))
    @GET
    @Path("/receive")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response receive(@Valid final ReceiveRequest request) {

        LOGGER.debug("Received receive request");

        Base64.Decoder decoder = Base64.getDecoder();

        MessageHash transactionHash =
                Optional.of(request).map(ReceiveRequest::getKey).map(decoder::decode).map(MessageHash::new).get();

        PublicKey recipient =
                Optional.of(request)
                        .map(ReceiveRequest::getTo)
                        .filter(Predicate.not(String::isEmpty))
                        .filter(Objects::nonNull)
                        .map(decoder::decode)
                        .map(PublicKey::from)
                        .orElse(null);

        com.quorum.tessera.transaction.ReceiveRequest receiveRequest =
                com.quorum.tessera.transaction.ReceiveRequest.Builder.create()
                        .withTransactionHash(transactionHash)
                        .withRecipient(recipient)
                        .withRaw(request.isRaw())
                        .build();

        com.quorum.tessera.transaction.ReceiveResponse response = transactionManager.receive(receiveRequest);

        ReceiveResponse receiveResponse = new ReceiveResponse();
        receiveResponse.setPrivacyFlag(response.getPrivacyMode().getPrivacyFlag());
        receiveResponse.setPayload(response.getUnencryptedTransactionData());
        Optional.ofNullable(response.getExecHash()).map(String::new).ifPresent(receiveResponse::setExecHash);

        String[] affectedTransactions =
                response.getAffectedTransactions().stream()
                        .map(MessageHash::getHashBytes)
                        .map(Base64.getEncoder()::encodeToString)
                        .toArray(String[]::new);

        receiveResponse.setAffectedContractTransactions(affectedTransactions);

        return Response.status(Status.OK).type(APPLICATION_JSON).entity(receiveResponse).build();
    }

    @Operation(
            summary = "/receiveraw",
            operationId = "getDecryptedPayloadOctetStream",
            description = "get payload from database, decrypt, and return")
    @ApiResponse(
            responseCode = "200",
            description = "decrypted ciphertext payload",
            content =
                    @Content(
                            array =
                                    @ArraySchema(
                                            schema =
                                                    @Schema(
                                                            type = "string",
                                                            format = "byte",
                                                            description = "decrypted ciphertext payload"))))
    @GET
    @Path("receiveraw")
    @Consumes(APPLICATION_OCTET_STREAM)
    @Produces(APPLICATION_OCTET_STREAM)
    public Response receiveRaw(
            @Schema(description = "hash indicating encrypted payload to retrieve from database", format = "base64")
                    @ValidBase64
                    @NotNull
                    @HeaderParam(value = "c11n-key")
                    String hash,
            @Schema(
                            description =
                                    "(optional) public key of recipient of the encrypted payload; used in decryption; if not provided, decryption is attempted with all known recipient keys in turn",
                            format = "base64")
                    @ValidBase64
                    @HeaderParam(value = "c11n-to")
                    String recipientKey) {

        LOGGER.debug("Received receiveraw request for hash : {}, recipientKey: {}", hash, recipientKey);

        MessageHash transactionHash = Optional.of(hash).map(Base64.getDecoder()::decode).map(MessageHash::new).get();
        PublicKey recipient =
                Optional.ofNullable(recipientKey).map(Base64.getDecoder()::decode).map(PublicKey::from).orElse(null);
        com.quorum.tessera.transaction.ReceiveRequest request =
                com.quorum.tessera.transaction.ReceiveRequest.Builder.create()
                        .withTransactionHash(transactionHash)
                        .withRecipient(recipient)
                        .build();

        com.quorum.tessera.transaction.ReceiveResponse receiveResponse = transactionManager.receive(request);

        byte[] payload = receiveResponse.getUnencryptedTransactionData();

        return Response.status(Status.OK).entity(payload).build();
    }

    @Deprecated
    @Operation(summary = "/delete", operationId = "deleteDeprecated", description = "delete payload from database")
    @ApiResponse(
            responseCode = "200",
            description = "delete successful",
            content =
                    @Content(schema = @Schema(type = "string"), examples = @ExampleObject(value = "Delete successful")))
    @POST
    @Path("delete")
    @Consumes(APPLICATION_JSON)
    @Produces(TEXT_PLAIN)
    public Response delete(@Valid final DeleteRequest deleteRequest) {

        LOGGER.debug("Received deprecated delete request");

        MessageHash messageHash =
                Optional.of(deleteRequest)
                        .map(DeleteRequest::getKey)
                        .map(Base64.getDecoder()::decode)
                        .map(MessageHash::new)
                        .get();

        transactionManager.delete(messageHash);

        return Response.status(Response.Status.OK).entity("Delete successful").build();
    }
}
