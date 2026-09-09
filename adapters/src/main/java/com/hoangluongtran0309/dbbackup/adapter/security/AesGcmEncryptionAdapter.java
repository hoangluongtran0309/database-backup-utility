package com.hoangluongtran0309.dbbackup.adapter.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.core.exception.EncryptionException;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;

/**
 * AES-256-GCM over target passwords, wire format
 * {@code Base64(IV[12] || ciphertext || tag[16])}.
 *
 * <p>A fresh 12-byte IV is drawn for every call. That is not a nicety: GCM
 * fails catastrophically if an IV is ever reused under the same key, and it
 * also means two targets sharing a password do not share a ciphertext, so the
 * table itself gives nothing away.
 *
 * <p>The key is 32 raw bytes supplied Base64-encoded. It is not derived from a
 * passphrase — deriving one here would invite a low-entropy secret to look
 * like a 256-bit key.
 */
@Component
public class AesGcmEncryptionAdapter implements EncryptionPort {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    AesGcmEncryptionAdapter(@Value("${dbbackup.encryption.key}") String base64Key) {
        this.key = new SecretKeySpec(decodeKey(base64Key), "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new EncryptionException("Cannot encrypt a null value");
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            // doFinal appends the authentication tag itself; there is no tag to splice on.
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] output = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(sealed, 0, output, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(output);
        } catch (GeneralSecurityException e) {
            // Deliberately no plaintext and no key material in the message: this
            // travels into logs and stack traces.
            throw new EncryptionException("Encryption failed", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new EncryptionException("Cannot decrypt a blank value");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException e) {
            throw new EncryptionException("Stored secret is not valid Base64", e);
        }
        if (decoded.length <= IV_LENGTH_BYTES) {
            throw new EncryptionException("Stored secret is too short to contain an IV and a tag");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_LENGTH_BITS, decoded, 0, IV_LENGTH_BYTES));
            byte[] plaintext = cipher.doFinal(
                    decoded, IV_LENGTH_BYTES, decoded.length - IV_LENGTH_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // GCM cannot tell these two apart, and saying so is more useful
            // than guessing at one of them.
            throw new EncryptionException(
                    "Could not decrypt stored secret: wrong key, or the ciphertext was tampered with", e);
        }
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException(
                    "dbbackup.encryption.key is not set. Generate one with: openssl rand -base64 32");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("dbbackup.encryption.key must be Base64 encoded", e);
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            // Length only — never the value.
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalArgumentException(
                    "dbbackup.encryption.key must decode to exactly %d bytes. Generate one with: openssl rand -base64 32"
                            .formatted(KEY_LENGTH_BYTES));
        }
        return decoded;
    }
}
