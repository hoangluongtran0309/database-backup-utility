package com.hoangluongtran0309.dbbackup.core.exception;

/**
 * Encrypting or decrypting a stored secret failed.
 *
 * <p>The message never carries the plaintext, the ciphertext, or the key: this
 * exception travels into logs and stack traces.
 */
public class EncryptionException extends RuntimeException {

    public EncryptionException(String message) {
        super(message);
    }

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
