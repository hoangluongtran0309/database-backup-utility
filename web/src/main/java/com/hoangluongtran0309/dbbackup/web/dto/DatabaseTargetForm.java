package com.hoangluongtran0309.dbbackup.web.dto;

import java.util.UUID;

import com.hoangluongtran0309.dbbackup.application.target.RegisterTargetCommand;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * The registration form's backing bean.
 *
 * <p>A mutable bean because Spring's data binder needs setters, and the domain
 * model deliberately has none. The constraints here duplicate the model's own
 * checks on purpose: these produce per-field messages next to the input, while
 * the model's protect the invariant no matter which caller reaches it.
 */
@Data
public class DatabaseTargetForm {

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    @NotNull(message = "Database engine is required")
    private DatabaseEngine engine;

    @Size(max = 255, message = "Host must be at most 255 characters")
    private String host;

    @Min(value = 1, message = "Port must be between 1 and 65535")
    @Max(value = 65535, message = "Port must be between 1 and 65535")
    private Integer port;

    @NotBlank(message = "Database name is required")
    @Size(max = 1024, message = "Database must be at most 1024 characters")
    private String database;

    @Size(max = 128, message = "Username must be at most 128 characters")
    private String username;

    @Size(max = 64, message = "Authentication database must be at most 64 characters")
    private String authenticationDatabase;

    @Size(max = 128, message = "Data Pump directory must be at most 128 characters")
    private String dataPumpDirectory;

    private String password;
    private UUID storageProfileId;
    private boolean verifyAfterBackup;

    public static DatabaseTargetForm blank() {
        DatabaseTargetForm form = new DatabaseTargetForm();
        form.setEngine(DatabaseEngine.MYSQL);
        form.setPort(DatabaseEngine.MYSQL.defaultPort());
        return form;
    }

    public RegisterTargetCommand toCommand() {
        return new RegisterTargetCommand(
                name, engine, host, port, database, username, password, authenticationDatabase,
                dataPumpDirectory, storageProfileId, verifyAfterBackup);
    }
}
