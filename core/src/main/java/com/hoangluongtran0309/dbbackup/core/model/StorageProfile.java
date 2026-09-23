package com.hoangluongtran0309.dbbackup.core.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import lombok.Builder;
import lombok.Getter;

/** A named remote artifact destination. Secrets are always encrypted at rest. */
@Getter
public final class StorageProfile {

    private final UUID id;
    private final String name;
    private final StorageProvider provider;
    private final String endpoint;
    private final String region;
    private final String bucket;
    private final String keyPrefix;
    private final boolean pathStyle;
    private final StorageCredentialMode credentialMode;
    private final String accessKeyId;
    private final String secretAccessKeyCiphertext;
    private final String projectId;
    private final String serviceAccountJsonCiphertext;
    private final String accountName;
    private final String accountKeyCiphertext;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final ConnectionCheck lastConnectionCheck;

    @Builder(toBuilder = true)
    private StorageProfile(
            UUID id,
            String name,
            StorageProvider provider,
            String endpoint,
            String region,
            String bucket,
            String keyPrefix,
            boolean pathStyle,
            StorageCredentialMode credentialMode,
            String accessKeyId,
            String secretAccessKeyCiphertext,
            String projectId,
            String serviceAccountJsonCiphertext,
            String accountName,
            String accountKeyCiphertext,
            Instant createdAt,
            Instant updatedAt,
            ConnectionCheck lastConnectionCheck) {
        this.id = require(id, "Profile id is required");
        this.name = text(name, "Profile name", 100);
        this.provider = require(provider, "Storage provider is required");
        this.endpoint = endpoint(endpoint);
        this.bucket = text(bucket, "Bucket", 255);
        this.keyPrefix = prefix(keyPrefix);
        this.credentialMode = require(credentialMode, "Credential mode is required");
        switch (provider) {
            case S3 -> {
                this.region = text(region, "Region", 64);
                this.pathStyle = pathStyle;
                this.projectId = absent(projectId, "S3 profile must not contain a Google Cloud project ID");
                this.serviceAccountJsonCiphertext = absent(serviceAccountJsonCiphertext,
                        "S3 profile must not contain Google service-account credentials");
                this.accountName = absent(accountName, "S3 profile must not contain an Azure account name");
                this.accountKeyCiphertext = absent(accountKeyCiphertext,
                        "S3 profile must not contain an Azure account key");
                if (credentialMode == StorageCredentialMode.STATIC) {
                    this.accessKeyId = text(accessKeyId, "Access key ID", 256);
                    this.secretAccessKeyCiphertext = text(secretAccessKeyCiphertext, "Secret access key", 4096);
                } else if (credentialMode == StorageCredentialMode.DEFAULT_CHAIN) {
                    this.accessKeyId = absent(accessKeyId,
                            "Default credential chain must not store static credentials");
                    this.secretAccessKeyCiphertext = absent(secretAccessKeyCiphertext,
                            "Default credential chain must not store static credentials");
                } else {
                    throw new IllegalArgumentException("S3 profile has an invalid credential mode");
                }
            }
            case GCS -> {
                this.region = absent(region, "GCS profile must not contain an S3 region");
                if (pathStyle) throw new IllegalArgumentException("GCS profile must not enable S3 path-style access");
                this.pathStyle = false;
                this.accessKeyId = absent(accessKeyId, "GCS profile must not contain an S3 access key");
                this.secretAccessKeyCiphertext = absent(secretAccessKeyCiphertext,
                        "GCS profile must not contain an S3 secret key");
                this.accountName = absent(accountName, "GCS profile must not contain an Azure account name");
                this.accountKeyCiphertext = absent(accountKeyCiphertext,
                        "GCS profile must not contain an Azure account key");
                this.projectId = text(projectId, "Google Cloud project ID", 255);
                if (credentialMode == StorageCredentialMode.SERVICE_ACCOUNT_JSON) {
                    this.serviceAccountJsonCiphertext = text(
                            serviceAccountJsonCiphertext, "Service-account JSON", 131072);
                } else if (credentialMode == StorageCredentialMode.APPLICATION_DEFAULT) {
                    this.serviceAccountJsonCiphertext = absent(serviceAccountJsonCiphertext,
                            "Application Default Credentials must not store service-account JSON");
                } else {
                    throw new IllegalArgumentException("GCS profile has an invalid credential mode");
                }
            }
            case AZURE_BLOB -> {
                this.region = absent(region, "Azure profile must not contain an S3 region");
                if (pathStyle) throw new IllegalArgumentException("Azure profile must not enable S3 path-style access");
                this.pathStyle = false;
                this.accessKeyId = absent(accessKeyId, "Azure profile must not contain an S3 access key");
                this.secretAccessKeyCiphertext = absent(secretAccessKeyCiphertext,
                        "Azure profile must not contain an S3 secret key");
                this.projectId = absent(projectId, "Azure profile must not contain a Google Cloud project ID");
                this.serviceAccountJsonCiphertext = absent(serviceAccountJsonCiphertext,
                        "Azure profile must not contain Google service-account credentials");
                this.accountName = accountName(accountName);
                if (credentialMode == StorageCredentialMode.ACCOUNT_KEY) {
                    this.accountKeyCiphertext = text(accountKeyCiphertext, "Account key", 4096);
                } else if (credentialMode == StorageCredentialMode.AZURE_DEFAULT) {
                    this.accountKeyCiphertext = absent(accountKeyCiphertext,
                            "Azure Default Credential must not store an account key");
                } else {
                    throw new IllegalArgumentException("Azure profile has an invalid credential mode");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported storage provider: " + provider);
        }
        this.createdAt = require(createdAt, "Creation timestamp is required");
        this.updatedAt = require(updatedAt, "Update timestamp is required");
        this.lastConnectionCheck = lastConnectionCheck;
    }

    public boolean sameLocation(StorageProfile other) {
        if (provider != other.provider) return false;
        boolean common = Objects.equals(endpoint, other.endpoint)
                && bucket.equals(other.bucket)
                && keyPrefix.equals(other.keyPrefix);
        return switch (provider) {
            case S3 -> common && region.equals(other.region) && pathStyle == other.pathStyle;
            case GCS -> common && projectId.equals(other.projectId);
            case AZURE_BLOB -> common && accountName.equals(other.accountName);
        };
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

    private static String accountName(String value) {
        String result = text(value, "Azure storage account name", 24);
        if (!result.matches("[a-z0-9]{3,24}")) {
            throw new IllegalArgumentException(
                    "Azure storage account name must be 3 to 24 lower-case letters or digits");
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

    private static String absent(String value, String message) {
        if (present(value)) throw new IllegalArgumentException(message);
        return null;
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
