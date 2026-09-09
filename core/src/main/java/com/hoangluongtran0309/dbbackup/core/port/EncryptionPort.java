package com.hoangluongtran0309.dbbackup.core.port;

/**
 * Symmetric encryption for secrets held at rest.
 *
 * <p>Only the application layer may call this. Adapters never hold the key,
 * so there is exactly one place in the system where a secret is unwrapped and
 * exactly one place to review.
 */
public interface EncryptionPort {

    /**
     * @return Base64 of {@code IV || ciphertext || tag}; encrypting the same
     *         input twice yields different output, because the IV is random
     * @throws com.hoangluongtran0309.dbbackup.core.exception.EncryptionException
     *         if encryption fails
     */
    String encrypt(String plaintext);

    /**
     * @throws com.hoangluongtran0309.dbbackup.core.exception.EncryptionException
     *         if the input is malformed, was encrypted under a different key,
     *         or has been tampered with
     */
    String decrypt(String ciphertext);
}
