package com.hoangluongtran0309.dbbackup.adapter.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.hoangluongtran0309.dbbackup.core.exception.EncryptionException;

class AesGcmEncryptionAdapterTest {

    private static final String KEY = base64Key(32);
    private static final String OTHER_KEY = Base64.getEncoder()
            .encodeToString("a-completely-different-32-byte!!".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private final AesGcmEncryptionAdapter adapter = new AesGcmEncryptionAdapter(KEY);

    @Test
    void roundTripsAValue() {
        assertThat(adapter.decrypt(adapter.encrypt("s3cr3t"))).isEqualTo("s3cr3t");
    }

    @Test
    void roundTripsNonAsciiAndEmptyValues() {
        assertThat(adapter.decrypt(adapter.encrypt("mật khẩu ✓"))).isEqualTo("mật khẩu ✓");
        assertThat(adapter.decrypt(adapter.encrypt(""))).isEmpty();
    }

    /**
     * The property that matters most here. A deterministic ciphertext would
     * both leak that two targets share a password and, far worse, mean a
     * repeated GCM IV.
     */
    @Test
    void encryptingTheSameValueTwiceGivesDifferentCiphertext() {
        String first = adapter.encrypt("same");
        String second = adapter.encrypt("same");

        assertThat(first).isNotEqualTo(second);
        assertThat(adapter.decrypt(first)).isEqualTo(adapter.decrypt(second)).isEqualTo("same");
    }

    @Test
    void prefixesTwelveBytesOfIv() {
        byte[] decoded = Base64.getDecoder().decode(adapter.encrypt("x"));

        // 12-byte IV + 16-byte tag + 1 byte of payload.
        assertThat(decoded).hasSize(12 + 16 + 1);
    }

    @Test
    void refusesCiphertextEncryptedUnderAnotherKey() {
        String sealed = new AesGcmEncryptionAdapter(OTHER_KEY).encrypt("s3cr3t");

        assertThatThrownBy(() -> adapter.decrypt(sealed))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("wrong key");
    }

    @Test
    void refusesTamperedCiphertext() {
        byte[] sealed = Base64.getDecoder().decode(adapter.encrypt("s3cr3t"));
        sealed[sealed.length - 1] ^= 0x01;

        String tampered = Base64.getEncoder().encodeToString(sealed);
        assertThatThrownBy(() -> adapter.decrypt(tampered)).isInstanceOf(EncryptionException.class);
    }

    @Test
    void refusesInputTooShortToHoldAnIvAndTag() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[12]);

        assertThatThrownBy(() -> adapter.decrypt(tooShort))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("too short");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64 at all !!", "", "   "})
    void refusesMalformedStoredSecrets(String malformed) {
        assertThatThrownBy(() -> adapter.decrypt(malformed)).isInstanceOf(EncryptionException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 24, 31, 33})
    void refusesAKeyThatIsNotThirtyTwoBytes(int length) {
        String key = base64Key(length);

        assertThatThrownBy(() -> new AesGcmEncryptionAdapter(key))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 32 bytes");
    }

    @Test
    void refusesToStartWithNoKeyConfigured() {
        assertThatThrownBy(() -> new AesGcmEncryptionAdapter(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    void refusesAKeyThatIsNotBase64() {
        assertThatThrownBy(() -> new AesGcmEncryptionAdapter("******not base64******"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base64");
    }

    private static String base64Key(int lengthBytes) {
        byte[] key = new byte[lengthBytes];
        for (int i = 0; i < lengthBytes; i++) {
            key[i] = (byte) i;
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
