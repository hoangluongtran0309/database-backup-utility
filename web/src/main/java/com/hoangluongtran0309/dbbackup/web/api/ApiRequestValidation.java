package com.hoangluongtran0309.dbbackup.web.api;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannel;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProvider;
import com.hoangluongtran0309.dbbackup.web.api.ApiEnvelope.ApiFieldError;
import com.hoangluongtran0309.dbbackup.web.api.OperatorApiController.NotificationRequest;
import com.hoangluongtran0309.dbbackup.web.api.OperatorApiController.RestoreRequest;
import com.hoangluongtran0309.dbbackup.web.api.OperatorApiController.RetentionRequest;
import com.hoangluongtran0309.dbbackup.web.api.OperatorApiController.ScheduleRequest;
import com.hoangluongtran0309.dbbackup.web.api.OperatorApiController.StorageRequest;
import com.hoangluongtran0309.dbbackup.web.api.OperatorApiController.SubscriptionRequest;
import com.hoangluongtran0309.dbbackup.web.api.OperatorApiController.TargetRequest;

/** Transport-level validation that reports independent request errors together. */
final class ApiRequestValidation {
    private static final int MAX_SERVICE_ACCOUNT_BYTES = 64 * 1024;

    private ApiRequestValidation() { }

    static void target(TargetRequest request, DatabaseEngine engine, boolean creating) {
        Collector errors = new Collector();
        errors.text(request.name(), "name", "Name", 100, true);
        if (creating && request.engine() == null) {
            errors.add("engine", "Database engine is required");
        }
        if (engine != null && engine.isFileBased()) {
            errors.absent(request.host(), "host", "Host is not used by SQLite targets");
            if (request.port() != null) errors.add("port", "Port is not used by SQLite targets");
        } else if (engine != null) {
            errors.text(request.host(), "host", "Host", 255, true);
            errors.port(request.port());
        }
        if (creating) {
            int databaseLimit = engine == DatabaseEngine.POSTGRESQL ? 63
                    : engine == DatabaseEngine.ORACLE ? 255
                    : engine == DatabaseEngine.SQLSERVER ? 128
                    : engine == DatabaseEngine.SQLITE ? 1024 : 64;
            errors.text(request.database(), "database", engine == DatabaseEngine.SQLITE
                    ? "Database file" : "Database name", databaseLimit, true);
            if (engine == DatabaseEngine.SQLITE && present(request.database())) {
                validateSqlitePath(request.database(), errors);
            }
        }
        if (engine != null) {
            if (engine.isFileBased()) {
                errors.absent(request.username(), "username", "Username is not used by SQLite targets");
                errors.absent(request.password(), "password", "Password is not used by SQLite targets");
            } else {
                int usernameLimit = engine == DatabaseEngine.MYSQL ? 32
                        : engine == DatabaseEngine.POSTGRESQL ? 63 : 128;
                errors.text(request.username(), "username", "Username", usernameLimit, true);
                int passwordLimit = engine == DatabaseEngine.SQLSERVER ? 128 : 8192;
                errors.text(request.password(), "password", "Password", passwordLimit, creating);
                if (engine == DatabaseEngine.ORACLE) {
                    errors.oracleIdentifier(request.username(), "username", "Oracle schema");
                    errors.text(request.dataPumpDirectory(), "dataPumpDirectory", "Data Pump directory", 128, true);
                    errors.oracleIdentifier(request.dataPumpDirectory(), "dataPumpDirectory", "Data Pump directory");
                    if (present(request.password()) && containsLineBreak(request.password())) {
                        errors.add("password", "Oracle password must not contain line breaks");
                    }
                } else {
                    errors.absent(request.dataPumpDirectory(), "dataPumpDirectory",
                            "Data Pump directory is only used by Oracle targets");
                }
                if (engine == DatabaseEngine.SQLSERVER && present(request.password())) {
                    if (request.password().length() <= 128
                            && (containsLineBreak(request.password()) || request.password().indexOf('\0') >= 0)) {
                        errors.add("password", "SQL Server password must not contain line breaks or NUL characters");
                    }
                }
            }
            if (engine == DatabaseEngine.MONGODB) {
                errors.text(request.authenticationDatabase(), "authenticationDatabase",
                        "Authentication database", 64, true);
            } else {
                errors.absent(request.authenticationDatabase(), "authenticationDatabase",
                        "Authentication database is only used by MongoDB targets");
            }
            if (Boolean.TRUE.equals(request.verifyAfterBackup()) && !engine.supportsRestoreVerification()) {
                errors.add("verifyAfterBackup",
                        "Automatic restore verification is not supported for " + engine.displayName());
            }
        }
        errors.finish();
    }

    static void storage(StorageRequest request, boolean creating) {
        Collector errors = new Collector();
        errors.text(request.name(), "name", "Profile name", 100, true);
        if (request.provider() == null) errors.add("provider", "Storage provider is required");
        validateEndpoint(request.endpoint(), errors);
        errors.text(request.bucket(), "bucket", "Bucket", 255, true);
        validatePrefix(request.keyPrefix(), errors);
        if (request.credentialMode() == null) errors.add("credentialMode", "Credential mode is required");
        if (request.serviceAccountJson() != null
                && request.serviceAccountJson().getBytes(StandardCharsets.UTF_8).length > MAX_SERVICE_ACCOUNT_BYTES) {
            errors.add("serviceAccountJson", "Service-account JSON must be at most 64 KiB");
        }

        StorageProvider provider = request.provider();
        StorageCredentialMode mode = request.credentialMode();
        if (provider == StorageProvider.S3) {
            errors.text(request.region(), "region", "Region", 64, true);
            errors.absent(request.projectId(), "projectId", "S3 profile must not contain a Google Cloud project ID");
            errors.absent(request.accountName(), "accountName", "S3 profile must not contain an Azure account name");
            errors.absent(request.serviceAccountJson(), "serviceAccountJson",
                    "S3 profile must not contain Google service-account credentials");
            errors.absent(request.accountKey(), "accountKey", "S3 profile must not contain an Azure account key");
            if (mode == StorageCredentialMode.STATIC) {
                errors.text(request.accessKeyId(), "accessKeyId", "Access key ID", 256, true);
                if (creating) errors.text(request.secretAccessKey(), "secretAccessKey", "Secret access key", 4096, true);
            } else if (mode == StorageCredentialMode.DEFAULT_CHAIN) {
                errors.absent(request.accessKeyId(), "accessKeyId",
                        "Default credential chain must not store static credentials");
                errors.absent(request.secretAccessKey(), "secretAccessKey",
                        "Default credential chain must not store static credentials");
            } else if (mode != null) {
                errors.add("credentialMode", "S3 profile has an invalid credential mode");
            }
        } else if (provider == StorageProvider.GCS) {
            errors.absent(request.region(), "region", "GCS profile must not contain an S3 region");
            if (Boolean.TRUE.equals(request.pathStyle())) {
                errors.add("pathStyle", "GCS profile must not enable S3 path-style access");
            }
            errors.absent(request.accessKeyId(), "accessKeyId", "GCS profile must not contain an S3 access key");
            errors.absent(request.secretAccessKey(), "secretAccessKey", "GCS profile must not contain an S3 secret key");
            errors.absent(request.accountName(), "accountName", "GCS profile must not contain an Azure account name");
            errors.absent(request.accountKey(), "accountKey", "GCS profile must not contain an Azure account key");
            errors.text(request.projectId(), "projectId", "Google Cloud project ID", 255, true);
            if (mode == StorageCredentialMode.SERVICE_ACCOUNT_JSON) {
                if (creating) errors.text(request.serviceAccountJson(), "serviceAccountJson",
                        "Service-account JSON", 131072, true);
            } else if (mode == StorageCredentialMode.APPLICATION_DEFAULT) {
                errors.absent(request.serviceAccountJson(), "serviceAccountJson",
                        "Application Default Credentials must not store service-account JSON");
            } else if (mode != null) {
                errors.add("credentialMode", "GCS profile has an invalid credential mode");
            }
        } else if (provider == StorageProvider.AZURE_BLOB) {
            errors.absent(request.region(), "region", "Azure profile must not contain an S3 region");
            if (Boolean.TRUE.equals(request.pathStyle())) {
                errors.add("pathStyle", "Azure profile must not enable S3 path-style access");
            }
            errors.absent(request.accessKeyId(), "accessKeyId", "Azure profile must not contain an S3 access key");
            errors.absent(request.secretAccessKey(), "secretAccessKey", "Azure profile must not contain an S3 secret key");
            errors.absent(request.projectId(), "projectId", "Azure profile must not contain a Google Cloud project ID");
            errors.absent(request.serviceAccountJson(), "serviceAccountJson",
                    "Azure profile must not contain Google service-account credentials");
            errors.text(request.accountName(), "accountName", "Azure storage account name", 24, true);
            if (present(request.accountName()) && request.accountName().strip().length() <= 24
                    && !request.accountName().strip().matches("[a-z0-9]{3,24}")) {
                errors.add("accountName", "Azure storage account name must be 3 to 24 lower-case letters or digits");
            }
            if (mode == StorageCredentialMode.ACCOUNT_KEY) {
                if (creating) errors.text(request.accountKey(), "accountKey", "Account key", 4096, true);
            } else if (mode == StorageCredentialMode.AZURE_DEFAULT) {
                errors.absent(request.accountKey(), "accountKey",
                        "Azure Default Credential must not store an account key");
            } else if (mode != null) {
                errors.add("credentialMode", "Azure profile has an invalid credential mode");
            }
        }
        errors.finish();
    }

    static void notification(NotificationRequest request, boolean creating) {
        Collector errors = new Collector();
        errors.text(request.name(), "name", "Channel name", 100, true);
        if (request.type() == null) errors.add("type", "Channel type is required");
        if (request.type() == NotificationChannelType.TELEGRAM) {
            if (creating) errors.text(request.botToken(), "botToken", "Telegram bot token", 8192, true);
            errors.text(request.chatId(), "chatId", "Telegram chat id", 100, true);
            errors.absent(request.webhookUrl(), "webhookUrl", "Telegram channel must not contain a webhook URL");
            errors.absent(request.emailTo(), "emailTo", "Telegram channel must not contain an email recipient");
        } else if (request.type() == NotificationChannelType.SLACK
                || request.type() == NotificationChannelType.WEBHOOK) {
            errors.absent(request.botToken(), "botToken", "Webhook channel must not contain a Telegram token");
            errors.absent(request.chatId(), "chatId", "Webhook channel must not contain a Telegram chat id");
            if (creating || present(request.webhookUrl())) {
                try {
                    NotificationChannel.validateWebhookUrl(
                            request.webhookUrl(), request.type() == NotificationChannelType.SLACK);
                } catch (IllegalArgumentException error) {
                    errors.add("webhookUrl", error.getMessage());
                }
            }
            errors.absent(request.emailTo(), "emailTo", "Webhook channel must not contain an email recipient");
        } else if (request.type() == NotificationChannelType.EMAIL) {
            errors.absent(request.botToken(), "botToken", "Email channel must not contain a Telegram token");
            errors.absent(request.chatId(), "chatId", "Email channel must not contain a Telegram chat id");
            errors.absent(request.webhookUrl(), "webhookUrl", "Email channel must not contain a webhook URL");
            errors.text(request.emailTo(), "emailTo", "Email recipient", 255, true);
            if (present(request.emailTo()) && !request.emailTo().strip().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                errors.add("emailTo", "Email recipient is not valid");
            }
        }
        errors.finish();
    }

    static void subscriptions(SubscriptionRequest request) {
        Collector errors = new Collector();
        if (request.subscriptions() != null) {
            Set<Object> channelIds = new HashSet<>();
            for (int index = 0; index < request.subscriptions().size(); index++) {
                var subscription = request.subscriptions().get(index);
                String prefix = "subscriptions[" + index + "]";
                if (subscription == null) {
                    errors.add(prefix, "Subscription is required");
                    continue;
                }
                if (subscription.channelId() == null) {
                    errors.add(prefix + ".channelId", "Notification channel is required");
                } else if (!channelIds.add(subscription.channelId())) {
                    errors.add(prefix + ".channelId", "A notification channel can only be selected once");
                }
                if (subscription.events() == null || subscription.events().isEmpty()) {
                    errors.add(prefix + ".events", "Choose at least one notification event");
                }
            }
        }
        errors.finish();
    }

    static void schedule(ScheduleRequest request) {
        Collector errors = new Collector();
        errors.text(request.name(), "name", "Name", 100, true);
        if (request.targetId() == null) errors.add("targetId", "Target is required");
        errors.text(request.cronExpression(), "cronExpression", "Cron expression", 120, true);
        if (present(request.cronExpression())) {
            int fields = request.cronExpression().strip().split("\\s+").length;
            if (fields < 6 || fields > 7) {
                errors.add("cronExpression", "Quartz cron expressions must contain six or seven fields");
            }
        }
        errors.text(request.zoneId(), "zoneId", "Time zone", 64, true);
        if (present(request.zoneId())) {
            try {
                ZoneId.of(request.zoneId().strip());
            } catch (ZoneRulesException error) {
                errors.add("zoneId", "Unknown time zone '%s'".formatted(request.zoneId().strip()));
            }
        }
        errors.finish();
    }

    static void retention(RetentionRequest request) {
        Collector errors = new Collector();
        if (request.keepSuccessful() == null || request.keepSuccessful() < 1) {
            errors.add("keepSuccessful", "Keep successful backups must be at least 1");
        }
        errors.finish();
    }

    static void restore(RestoreRequest request) {
        Collector errors = new Collector();
        if (request.backupExecutionId() == null) errors.add("backupExecutionId", "Backup execution is required");
        if (request.targetId() == null) errors.add("targetId", "Destination target is required");
        errors.text(request.confirmation(), "confirmation", "Confirmation", 100, true);
        errors.finish();
    }

    private static void validateSqlitePath(String value, Collector errors) {
        if (value.contains("\\")) {
            errors.add("database", "Database file must use '/' as its separator");
            return;
        }
        try {
            Path path = Path.of(value.strip());
            if (path.isAbsolute()) errors.add("database", "Database file must be relative to SQLITE_ROOT");
            for (Path part : path) {
                if (part.toString().equals(".") || part.toString().equals("..")) {
                    errors.add("database", "Database file must not contain '.' or '..' segments");
                    break;
                }
            }
        } catch (InvalidPathException error) {
            errors.add("database", "Database file is not a valid path");
        }
    }

    private static void validateEndpoint(String value, Collector errors) {
        if (!present(value)) return;
        String endpoint = value.strip();
        if (endpoint.length() > 2048) {
            errors.add("endpoint", "Endpoint must be at most 2048 characters");
            return;
        }
        try {
            URI uri = URI.create(endpoint);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                errors.add("endpoint", "Endpoint must use HTTP or HTTPS, include a host, and have no user-info, query, or fragment");
            }
        } catch (IllegalArgumentException error) {
            errors.add("endpoint", "Endpoint must be a valid HTTP or HTTPS URL");
        }
    }

    private static void validatePrefix(String value, Collector errors) {
        if (!present(value)) return;
        String prefix = value.strip().replaceAll("^/+|/+$", "");
        if (prefix.length() > 1024 || prefix.contains("//") || prefix.chars().anyMatch(Character::isISOControl)) {
            errors.add("keyPrefix", "Key prefix is invalid");
        }
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static final class Collector {
        private final List<ApiFieldError> errors = new ArrayList<>();

        void add(String field, String message) {
            ApiFieldError error = new ApiFieldError(field, message);
            if (!errors.contains(error)) errors.add(error);
        }

        void text(String value, String field, String label, int max, boolean required) {
            if (!present(value)) {
                if (required) add(field, label + " is required");
            } else if (value.strip().length() > max) {
                add(field, "%s must be at most %d characters".formatted(label, max));
            }
        }

        void absent(String value, String field, String message) {
            if (present(value)) add(field, message);
        }

        void port(Integer value) {
            if (value == null) add("port", "Port is required");
            else if (value < 1 || value > 65535) add("port", "Port must be between 1 and 65535");
        }

        void oracleIdentifier(String value, String field, String label) {
            if (present(value) && value.strip().length() <= 128
                    && !value.strip().matches("[A-Za-z][A-Za-z0-9_$#]*")) {
                add(field, label + " must be an unquoted Oracle identifier");
            }
        }

        void finish() {
            if (!errors.isEmpty()) throw new ApiValidationException(errors);
        }
    }
}
