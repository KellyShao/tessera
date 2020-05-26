package com.quorum.tessera.transaction;

import com.quorum.tessera.enclave.AffectedTransaction;
import com.quorum.tessera.enclave.EncodedPayload;
import com.quorum.tessera.enclave.PrivacyMode;
import com.quorum.tessera.enclave.TxHash;
import com.quorum.tessera.encryption.PublicKey;
import java.util.List;
import java.util.Set;

public interface PrivacyHelper {


    List<AffectedTransaction> findAffectedContractTransactionsFromSendRequest(String[] affectedHashes);

    List<AffectedTransaction> findAffectedContractTransactionsFromPayload(EncodedPayload payload);

    boolean validateSendRequest(
            PrivacyMode privacyMode, List<PublicKey> recipientList, List<AffectedTransaction> affectedTransactions);

    boolean validatePayload(
            TxHash txHash, EncodedPayload encodedPayload, List<AffectedTransaction> affectedTransactions);

    EncodedPayload sanitisePrivacyPayload(
            TxHash txHash, EncodedPayload encodedPayload, Set<TxHash> invalidSecurityHashes);
}