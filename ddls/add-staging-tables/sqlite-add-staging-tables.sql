CREATE TABLE ST_TRANSACTION (CIPHER_TEXT BLOB NOT NULL, CIPHER_TEXT_NONCE BLOB NOT NULL, EXEC_HASH BLOB, DATA_ISSUES VARCHAR, PRIVACY_MODE NUMBER(3), RECIPIENT_NONCE BLOB NOT NULL, SENDER_KEY BLOB NOT NULL, TIMESTAMP NUMBER(19), VALIDATION_STAGE NUMBER(19), HASH VARCHAR NOT NULL, PRIMARY KEY (HASH));
CREATE INDEX ST_TRANSACTION_VALSTG ON ST_TRANSACTION (VALIDATION_STAGE);
CREATE TABLE ST_AFFECTED_TRANSACTION (SECURITY_HASH BLOB, AFFECTED_HASH VARCHAR NOT NULL, SOURCE_HASH VARCHAR NOT NULL, PRIMARY KEY (AFFECTED_HASH, SOURCE_HASH), FOREIGN KEY (SOURCE_HASH) REFERENCES ST_TRANSACTION (HASH));
CREATE TABLE ST_TRANSACTION_RECIPIENT (BOX BLOB, INITIATOR NUMBER(1), HASH VARCHAR NOT NULL, RECIPIENT VARCHAR NOT NULL, PRIMARY KEY (HASH, RECIPIENT), FOREIGN KEY (HASH) REFERENCES ST_TRANSACTION (HASH));
CREATE TABLE ST_TRANSACTION_VERSION (NANOTIME NUMBER(19), PAYLOAD BLOB NOT NULL, PRIVACY_MODE NUMBER(3), TIMESTAMP NUMBER(19), HASH VARCHAR NOT NULL, RECIPIENT VARCHAR NOT NULL, PRIMARY KEY (HASH, RECIPIENT), FOREIGN KEY (HASH) REFERENCES ST_TRANSACTION (HASH));