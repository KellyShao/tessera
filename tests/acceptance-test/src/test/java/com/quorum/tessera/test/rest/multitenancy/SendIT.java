package com.quorum.tessera.test.rest.multitenancy;

import com.quorum.tessera.api.ReceiveResponse;
import com.quorum.tessera.api.SendRequest;
import com.quorum.tessera.api.SendResponse;
import com.quorum.tessera.config.keypairs.ConfigKeyPair;
import com.quorum.tessera.test.Party;
import com.quorum.tessera.test.PartyHelper;
import com.quorum.tessera.test.rest.RestUtils;
import config.ConfigDescriptor;
import org.junit.Test;
import suite.ExecutionContext;
import suite.NodeAlias;

import javax.json.Json;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static com.quorum.tessera.version.MultiTenancyVersion.MIME_TYPE_JSON_2_1;
import static org.assertj.core.api.Assertions.assertThat;
import static transaction.utils.Utils.generateValidButUnknownPublicKey;

/**
 * Scenarios tested:
 *
 * <p>- 1 sender, 1 private for - 1 sender, 2 private for - TODO: 1 sender, 2 private for, 1 is down - 0 sender, 1
 * private for - 1 sender, 0 private for - no payload - sending when it isn't json - sending to an unknown recipient -
 * TODO: send using an unknown sender key
 */
public class SendIT {

    private static final String SEND_PATH = "/send";

    private final Client client = ClientBuilder.newClient();

    private RestUtils utils = new RestUtils();

    private PartyHelper partyHelper = PartyHelper.create();

    /** Quorum sends transaction with single public recipient key */
    @Test
    public void sendToSingleRecipient() {
        Party firstParty = partyHelper.findByAlias("A");
        Party secondParty = partyHelper.findByAlias("B");
        byte[] transactionData = utils.createTransactionData();

        final SendRequest sendRequest = new SendRequest();
        sendRequest.setFrom(firstParty.getPublicKey());
        sendRequest.setTo(secondParty.getPublicKey());
        sendRequest.setPayload(transactionData);

        final Response response =
                client.target(firstParty.getQ2TUri())
                        .path(SEND_PATH)
                        .request()
                        .post(Entity.entity(sendRequest, MIME_TYPE_JSON_2_1));

        // validate result
        final SendResponse result = response.readEntity(SendResponse.class);
        assertThat(result.getKey()).isNotNull().isNotBlank();
        assertThat(result.getManagedParties()).containsExactlyInAnyOrder(firstParty.getPublicKey());

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(201);

        URI location = response.getLocation();

        final Response checkPersistedTxnResponse = client.target(location).request().get();

        assertThat(checkPersistedTxnResponse.getStatus()).isEqualTo(200);

        ReceiveResponse receiveResponse = checkPersistedTxnResponse.readEntity(ReceiveResponse.class);

        assertThat(receiveResponse.getPayload())
                .describedAs("The response payload should be equal to the sent txn data")
                .isEqualTo(transactionData);

        utils.findTransaction(result.getKey(), partyHelper.findByAlias("A"), partyHelper.findByAlias("B"))
                .forEach(r -> assertThat(r.getStatus()).isEqualTo(200));

        utils.findTransaction(result.getKey(), partyHelper.findByAlias("D"))
                .forEach(r -> assertThat(r.getStatus()).isEqualTo(404));
    }

    @Test
    public void sendToMultipleRecipientsOnSameNode() throws UnsupportedEncodingException {
        // Node C has 2 keys, use them both
        final String[] recipientPublicKeys =
                ExecutionContext.currentContext().getConfigs().stream()
                        .filter(c -> c.getAlias() == NodeAlias.C)
                        .findFirst()
                        .map(ConfigDescriptor::getAllKeys)
                        .get()
                        .stream()
                        .map(ConfigKeyPair::getPublicKey)
                        .toArray(String[]::new);

        final Party sendingParty = partyHelper.findByAlias("A");
        final Party recipientParty = partyHelper.findByAlias("C");

        final byte[] transactionData = utils.createTransactionData();

        final SendRequest sendRequest = new SendRequest();
        sendRequest.setFrom(sendingParty.getPublicKey());
        sendRequest.setTo(recipientPublicKeys);
        sendRequest.setPayload(transactionData);

        final Response response =
                client.target(sendingParty.getQ2TUri())
                        .path(SEND_PATH)
                        .request()
                        .post(Entity.entity(sendRequest, MIME_TYPE_JSON_2_1));

        //

        final SendResponse result = response.readEntity(SendResponse.class);
        assertThat(result.getKey()).isNotNull().isNotBlank();
        assertThat(result.getManagedParties()).containsExactlyInAnyOrder(sendingParty.getPublicKey());
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(201);
        URI location = response.getLocation();

        {
            final Response checkPersistedTxnResponse = client.target(location).request().get();
            assertThat(checkPersistedTxnResponse.getStatus()).isEqualTo(200);
            ReceiveResponse receiveResponse = checkPersistedTxnResponse.readEntity(ReceiveResponse.class);
            assertThat(receiveResponse.getPayload()).isEqualTo(transactionData);
            assertThat(result.getManagedParties()).containsExactlyInAnyOrder(sendingParty.getPublicKey());
        }
        {
            String encodedId = URLEncoder.encode(result.getKey(), StandardCharsets.UTF_8.toString());

            Stream.of(recipientParty)
                    .map(Party::getRestClientWebTarget)
                    .map(target -> target.path("transaction"))
                    .map(target -> target.path(encodedId))
                    .map(target -> target.request().accept(MIME_TYPE_JSON_2_1).get())
                    .forEach(
                            r -> {
                                assertThat(r.getStatus()).isEqualTo(200);
                                ReceiveResponse receiveResponse = r.readEntity(ReceiveResponse.class);
                                assertThat(receiveResponse.getManagedParties())
                                        .containsExactlyInAnyOrder(recipientPublicKeys);
                            });
        }
    }

    @Test
    public void senderAndRecipientOnSameNode() throws UnsupportedEncodingException {
        // Node C has 2 keys, use them both
        final String[] recipientPublicKeys =
                ExecutionContext.currentContext().getConfigs().stream()
                        .filter(c -> c.getAlias() == NodeAlias.C)
                        .findFirst()
                        .map(ConfigDescriptor::getAllKeys)
                        .get()
                        .stream()
                        .map(ConfigKeyPair::getPublicKey)
                        .toArray(String[]::new);

        final Party party = partyHelper.findByAlias("C");

        final byte[] transactionData = utils.createTransactionData();

        final SendRequest sendRequest = new SendRequest();
        sendRequest.setFrom(recipientPublicKeys[0]);
        sendRequest.setTo(recipientPublicKeys[1]);
        sendRequest.setPayload(transactionData);

        final Response response =
                client.target(party.getQ2TUri())
                        .path(SEND_PATH)
                        .request()
                        .post(Entity.entity(sendRequest, MIME_TYPE_JSON_2_1));

        //

        final SendResponse result = response.readEntity(SendResponse.class);
        assertThat(result.getKey()).isNotNull().isNotBlank();
        assertThat(result.getManagedParties()).containsExactlyInAnyOrder(recipientPublicKeys);
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(201);
        URI location = response.getLocation();

        {
            final Response checkPersistedTxnResponse = client.target(location).request().get();
            assertThat(checkPersistedTxnResponse.getStatus()).isEqualTo(200);
            ReceiveResponse receiveResponse = checkPersistedTxnResponse.readEntity(ReceiveResponse.class);
            assertThat(receiveResponse.getPayload()).isEqualTo(transactionData);
            assertThat(result.getManagedParties()).containsExactlyInAnyOrder(recipientPublicKeys);
        }
        {
            String encodedId = URLEncoder.encode(result.getKey(), StandardCharsets.UTF_8.toString());

            Stream.of(party)
                    .map(Party::getRestClientWebTarget)
                    .map(target -> target.path("transaction"))
                    .map(target -> target.path(encodedId))
                    .map(target -> target.request().accept(MIME_TYPE_JSON_2_1).get())
                    .forEach(
                            r -> {
                                assertThat(r.getStatus()).isEqualTo(200);
                                ReceiveResponse receiveResponse = r.readEntity(ReceiveResponse.class);
                                assertThat(receiveResponse.getManagedParties())
                                        .containsExactlyInAnyOrder(recipientPublicKeys);
                            });
        }
    }

    /** Quorum sends transaction with multiple public recipient keys */
    @Test
    public void firstPartyForwardsToTwoOtherParties() {

        final Party sendingParty = partyHelper.findByAlias("A");

        final Party secondParty = partyHelper.findByAlias("B");
        final Party thirdParty = partyHelper.findByAlias("D");

        final Party excludedParty = partyHelper.findByAlias("C");

        final byte[] transactionData = utils.createTransactionData();

        final SendRequest sendRequest = new SendRequest();
        sendRequest.setFrom(sendingParty.getPublicKey());
        sendRequest.setTo(secondParty.getPublicKey(), thirdParty.getPublicKey());
        sendRequest.setPayload(transactionData);

        final Response response =
                client.target(sendingParty.getQ2TUri())
                        .path(SEND_PATH)
                        .request()
                        .post(Entity.entity(sendRequest, MIME_TYPE_JSON_2_1));

        //
        final SendResponse result = response.readEntity(SendResponse.class);
        assertThat(result.getKey()).isNotNull().isNotBlank();
        assertThat(result.getManagedParties()).containsExactlyInAnyOrder(sendingParty.getPublicKey());

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(201);

        URI location = response.getLocation();

        final Response checkPersistedTxnResponse = client.target(location).request().get();

        assertThat(checkPersistedTxnResponse.getStatus()).isEqualTo(200);

        ReceiveResponse receiveResponse = checkPersistedTxnResponse.readEntity(ReceiveResponse.class);

        assertThat(receiveResponse.getPayload()).isEqualTo(transactionData);

        utils.findTransaction(result.getKey(), sendingParty, secondParty, thirdParty)
                .forEach(r -> assertThat(r.getStatus()).isEqualTo(200));

        utils.findTransaction(result.getKey(), excludedParty).forEach(r -> assertThat(r.getStatus()).isEqualTo(404));
    }

    @Test
    public void sendTransactionWithoutASender() {

        Party recipient = partyHelper.getParties().findAny().get();

        byte[] transactionData = utils.createTransactionData();

        final SendRequest sendRequest = new SendRequest();
        sendRequest.setTo(recipient.getPublicKey());
        sendRequest.setPayload(transactionData);

        final Response response =
                client.target(recipient.getQ2TUri())
                        .path(SEND_PATH)
                        .request()
                        .post(Entity.entity(sendRequest, MIME_TYPE_JSON_2_1));

        final SendResponse result = response.readEntity(SendResponse.class);
        assertThat(result.getKey()).isNotNull().isNotBlank();
        assertThat(result.getManagedParties()).containsExactlyInAnyOrder(recipient.getPublicKey());

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(201);

        URI location = response.getLocation();

        final Response checkPersistedTxnResponse = client.target(location).request().get();

        assertThat(checkPersistedTxnResponse.getStatus()).isEqualTo(200);

        ReceiveResponse receiveResponse = checkPersistedTxnResponse.readEntity(ReceiveResponse.class);

        assertThat(receiveResponse.getPayload()).isEqualTo(transactionData);
    }

    @Test
    public void sendTransactionWithMissingRecipients() {

        final Party sendingParty = partyHelper.getParties().findAny().get();
        final byte[] transactionData = utils.createTransactionData();

        final SendRequest sendRequest = new SendRequest();
        sendRequest.setFrom(sendingParty.getPublicKey());
        sendRequest.setPayload(transactionData);

        final Response response =
                client.target(sendingParty.getQ2TUri())
                        .path(SEND_PATH)
                        .request()
                        .post(Entity.entity(sendRequest, MIME_TYPE_JSON_2_1));

        final SendResponse result = response.readEntity(SendResponse.class);
        assertThat(result.getKey()).isNotNull().isNotBlank();
        assertThat(result.getManagedParties()).containsExactlyInAnyOrder(sendingParty.getPublicKey());

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(201);

        URI location = response.getLocation();

        final Response checkPersistedTxnResponse = client.target(location).request().get();

        assertThat(checkPersistedTxnResponse.getStatus()).isEqualTo(200);

        ReceiveResponse receiveResponse = checkPersistedTxnResponse.readEntity(ReceiveResponse.class);

        assertThat(receiveResponse.getPayload()).isEqualTo(transactionData);

        assertThat(location.getHost()).isEqualTo(sendingParty.getQ2TUri().getHost());
        assertThat(location.getPort()).isEqualTo(sendingParty.getQ2TUri().getPort());
    }

    @Test
    public void missingPayloadFails() {

        Party sendingParty = partyHelper.getParties().findAny().get();

        Party recipient = partyHelper.getParties().filter(p -> p != sendingParty).findAny().get();

        final String sendRequest =
                Json.createObjectBuilder()
                        .add("from", sendingParty.getPublicKey())
                        .add("to", Json.createArrayBuilder().add(recipient.getPublicKey()))
                        .build()
                        .toString();

        final Response response =
                client.target(sendingParty.getQ2TUri())
                        .path(SEND_PATH)
                        .request()
                        .post(Entity.entity(sendRequest, MIME_TYPE_JSON_2_1));

        // validate result
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    public void garbageMessageFails() {
        Party sendingParty = partyHelper.getParties().findAny().get();

        final String sendRequest = "this is clearly a garbage message";

        final Response response =
                client.target(sendingParty.getQ2TUri())
                        .path(SEND_PATH)
                        .request()
                        .post(Entity.entity(sendRequest, MIME_TYPE_JSON_2_1));

        // validate result
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    public void emptyMessageFails() {

        Party sendingParty = partyHelper.getParties().findAny().get();
        final String sendRequest = "{}";

        final Response response =
                client.target(sendingParty.getQ2TUri())
                        .path(SEND_PATH)
                        .request()
                        .post(Entity.entity(sendRequest, MIME_TYPE_JSON_2_1));

        // validate result
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    /** Quorum sends transaction with unknown public key */
    @Test
    public void sendUnknownPublicKey() {

        Party sendingParty = partyHelper.getParties().findAny().get();
        byte[] transactionData = utils.createTransactionData();

        final SendRequest sendRequest = new SendRequest();
        sendRequest.setFrom(sendingParty.getPublicKey());

        ExecutionContext executionContext = ExecutionContext.currentContext();

        final String unknownkey =
                generateValidButUnknownPublicKey(executionContext.getEncryptorType()).encodeToBase64();
        sendRequest.setTo(unknownkey);

        sendRequest.setPayload(transactionData);

        final Response response =
                client.target(sendingParty.getQ2TUri())
                        .path(SEND_PATH)
                        .request()
                        .post(Entity.entity(sendRequest, MIME_TYPE_JSON_2_1));

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    /** config3.json has party 1's key in always send to list */
    @Test
    public void partyAlwaysSendsToPartyOne() {

        Party sender = partyHelper.findByAlias("C");
        Party recipient = partyHelper.findByAlias("D");

        byte[] transactionData = utils.createTransactionData();

        final SendRequest sendRequest = new SendRequest();
        sendRequest.setFrom(sender.getPublicKey());
        sendRequest.setTo(recipient.getPublicKey());
        sendRequest.setPayload(transactionData);

        final Response response =
                client.target(sender.getQ2TUri())
                        .path(SEND_PATH)
                        .request()
                        .post(Entity.entity(sendRequest, MIME_TYPE_JSON_2_1));

        final SendResponse result = response.readEntity(SendResponse.class);
        assertThat(result.getKey()).isNotNull().isNotBlank();
        assertThat(result.getManagedParties()).containsExactlyInAnyOrder(sender.getPublicKey());

        // Party one received by always send to
        utils.findTransaction(result.getKey(), sender, recipient, partyHelper.findByAlias("A"))
                .forEach(r -> assertThat(r.getStatus()).isEqualTo(200));

        // Party 2 is out of the loop
        utils.findTransaction(result.getKey(), partyHelper.findByAlias("B"))
                .forEach(r -> assertThat(r.getStatus()).isEqualTo(404));
    }
}
