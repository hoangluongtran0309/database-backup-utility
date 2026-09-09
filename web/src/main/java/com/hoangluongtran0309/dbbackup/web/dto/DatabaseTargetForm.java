package com.hoangluongtran0309.dbbackup.web.dto;

import com.hoangluongtran0309.dbbackup.application.target.RegisterTargetCommand;

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

    private static final int MYSQL_DEFAULT_PORT = 3306;

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    @NotBlank(message = "Host is required")
    @Size(max = 255, message = "Host must be at most 255 characters")
    private String host;

    @NotNull(message = "Port is required")
    @Min(value = 1, message = "Port must be between 1 and 65535")
    @Max(value = 65535, message = "Port must be between 1 and 65535")
    private Integer port;

    @NotBlank(message = "Database name is required")
    @Size(max = 64, message = "Database name must be at most 64 characters")
    private String database;

    @NotBlank(message = "Username is required")
    @Size(max = 32, message = "Username must be at most 32 characters")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;

    public static DatabaseTargetForm blank() {
        DatabaseTargetForm form = new DatabaseTargetForm();
        form.setPort(MYSQL_DEFAULT_PORT);
        return form;
    }

    public RegisterTargetCommand toCommand() {
        return new RegisterTargetCommand(name, host, port, database, username, password);
    }
}
