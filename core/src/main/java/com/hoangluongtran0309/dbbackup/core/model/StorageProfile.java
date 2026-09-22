package com.hoangluongtran0309.dbbackup.core.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import lombok.Builder;
import lombok.Getter;

/** A named S3-compatible destination. Secrets are always encrypted at rest. */
@Getter
public final class StorageProfile {

    private final UUID id;
    private final String name;
    private final String endpoint;
    private final String region;
    private final String bucket;
    private final String keyPrefix;
    private final boolean pathStyle;
    private final StorageCredentialMode credentialMode;
    private final String accessKeyId;
    private final String secretAccessKeyCiphertext;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final ConnectionCheck lastConnectionCheck;

    @Builder(toBuilder = true)
    private StorageProfile(
            UUID id,
            String name,
            String endpoint,
            String region,
            String bucket,
            String keyPrefix,
            boolean pathStyle,
            StorageCredentialMode credentialMode,
            String accessKeyId,
            String secretAccessKeyCiphertext,
            Instant createdAt,
            Instant updatedAt,
            ConnectionCheck lastConnectionCheck) {
        this.id = require(id, "Profile id is required");
        this.name = text(name, "Profile name", 100);
        this.endpoint = endpoint(endpoint);
        this.region = text(region, "Region", 64);
        this.bucket = text(bucket, "Bucket", 255);
        this.keyPrefix = prefix(keyPrefix);
        this.pathStyle = pathStyle;
        this.credentialMode = require(credentialMode, "Credential mode is required");
        if (credentialMode == StorageCredentialMode.STATIC) {
            this.accessKeyId = text(accessKeyId, "Access key ID", 256);
            this.secretAccessKeyCiphertext = text(secretAccessKeyCiphertext, "Secret access key", 4096);
        } else {
            if (present(accessKeyId) || present(secretAccessKeyCiphertext)) {
                throw new IllegalArgumentException("Default credential chain must not store static credentials");
            }
            this.accessKeyId = null;
            this.secretAccessKeyCiphertext = null;
        }
        this.createdAt = require(createdAt, "Creation timestamp is required");
        this.updatedAt = require(updatedAt, "Update timestamp is required");
        this.lastConnectionCheck = lastConnectionCheck;
    }

    public boolean sameLocation(StorageProfile other) {
        return Objects.equals(endpoint, other.endpoint)
                && region.equals(other.region)
                && bucket.equals(other.bucket)
                && keyPrefix.equals(other.keyPrefix)
                && pathStyle == other.pathStyle;
    }

    public StorageProfile withConnectionCheck(ConnectionCheck check, Instant now) {
        return toBuilder().lastConnectionCheck(require(check, "Connection check is required")).updatedAt(now).build();
    }

    private static String endpoint(String value) {
        if (!present(value)) {
            return null;
        }
        String candidate = value.strip();
        if (candidate.length() > 2048) {
            throw new IllegalArgumentException("Endpoint must be at most 2048 characters");
        }
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Endpoint must be a valid HTTP or HTTPS URL");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Endpoint must use HTTP or HTTPS, include a host, and have no user-info, query, or fragment");
        }
        return candidate;
    }

    private static String prefix(String value) {
        if (!present(value)) {
            return "";
        }
        String result = value.strip().replaceAll("^/+|/+$", "");
        if (result.length() > 1024 || result.contains("//") || result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Key prefix is invalid");
        }
        return result;
    }

    private static String text(String value, String label, int max) {
        if (!present(value)) {
            throw new IllegalArgumentException(label + " is required");
        }
        String result = value.strip();
        if (result.length() > max) {
            throw new IllegalArgumentException(label + " must be at most " + max + " characters");
        }
        return result;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
