package com.hoangluongtran0309.dbbackup.web.dto;

import com.hoangluongtran0309.dbbackup.application.target.EditTargetCommand;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * The edit form's backing bean.
 *
 * <p>Not {@link DatabaseTargetForm} with a flag: two fields behave differently
 * here. There is no schema, because a target's schema is fixed once it is
 * registered, and the password may be left empty to keep the stored one — it
 * is never sent to the browser, so the field always starts out blank.
 */
@Data
public class EditTargetForm {

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

    @NotBlank(message = "Username is required")
    @Size(max = 63, message = "Username must be at most 63 characters")
    private String username;

    @Size(max = 64, message = "Authentication database must be at most 64 characters")
    private String authenticationDatabase;

    /** Blank keeps the stored password. */
    private String password;

    /** Pre-filled from the target, all but the password. */
    public static EditTargetForm of(DatabaseTarget target) {
        EditTargetForm form = new EditTargetForm();
        form.setName(target.getName());
        form.setHost(target.getHost());
        form.setPort(target.getPort());
        form.setUsername(target.getUsername());
        form.setAuthenticationDatabase(target.getAuthenticationDatabase());
        return form;
    }

    public EditTargetCommand toCommand() {
        return new EditTargetCommand(name, host, port, username, password, authenticationDatabase);
    }
}
