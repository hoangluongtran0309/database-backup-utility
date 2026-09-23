package com.hoangluongtran0309.dbbackup.web.controller;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ValidationUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.hoangluongtran0309.dbbackup.application.backup.RunBackupService;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.application.target.TestTargetConnectionService;
import com.hoangluongtran0309.dbbackup.application.storage.ManageStorageProfileService;
import com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException;
import com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException;
import com.hoangluongtran0309.dbbackup.core.exception.TargetInUseException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.web.dto.DatabaseTargetForm;
import com.hoangluongtran0309.dbbackup.web.dto.EditTargetForm;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Translates HTTP into use case calls and back. No business rules live here.
 */
@Controller
@RequestMapping("/databases")
@RequiredArgsConstructor
public class DatabaseTargetController {

    private static final String FORM_VIEW = "database/form";

    /**
     * Domain field names to form field names. A whitelist, not a guess: a name
     * that is not listed becomes a form-level error rather than being bound to
     * an input that may not exist.
     */
    private static final Map<String, String> FORM_FIELDS = Map.of(
            "name", "name",
            "engine", "engine",
            "host", "host",
            "port", "port",
            "databaseName", "database",
            "username", "username",
            "authenticationDatabase", "authenticationDatabase",
            "dataPumpDirectory", "dataPumpDirectory",
            "password", "password",
            "storageProfileId", "storageProfileId");

    private final ManageDatabaseTargetService service;
    private final TestTargetConnectionService connectionTest;
    private final RunBackupService backups;
    private final BackupExecutionRepository executions;
    private final ManageStorageProfileService storageProfiles;

    @ModelAttribute("engines")
    List<DatabaseEngine> engines() {
        return service.availableEngines();
    }

    @ModelAttribute("storageProfiles")
    Object storageProfiles() {
        return storageProfiles.listAll();
    }

    /**
     * Beside each target, its newest backup attempt and its newest successful
     * one — "when was this last backed up?" is the question the page exists to
     * answer, and a failed attempt must not hide the good copy before it.
     * Asked of the database per target, rather than by reading the whole
     * history to find the first of each.
     */
    @GetMapping
    String list(Model model) {
        model.addAttribute("targets", service.listAll());
        model.addAttribute("latestBackups", byTarget(executions.findLatestPerTarget()));
        model.addAttribute("lastSuccessfulBackups", byTarget(executions.findLatestSucceededPerTarget()));
        model.addAttribute("availableEngines", service.availableEngines());
        model.addAttribute("storageProfileNames", storageProfiles.listAll().stream()
                .collect(Collectors.toMap(p -> p.getId(),
                        p -> p.getName() + " — " + p.getProvider().getDisplayName())));
        return "database/list";
    }

    @GetMapping("/new")
    String newForm(Model model) {
        model.addAttribute("form", DatabaseTargetForm.blank());
        return FORM_VIEW;
    }

    @PostMapping
    String create(
            @Valid @ModelAttribute("form") DatabaseTargetForm form,
            BindingResult binding,
            RedirectAttributes flash) {

        validateRegistrationFields(form, binding);

        // Re-rendering must not replace the bound `form`: it is what the
        // operator typed, and losing a filled-in page to one bad field is the
        // fastest way to make people stop trusting the console.
        if (binding.hasErrors()) {
            return FORM_VIEW;
        }
        try {
            DatabaseTarget target = service.register(form.toCommand());
            flash.addFlashAttribute("message", "Registered target '%s'".formatted(target.getName()));
            return "redirect:/databases";
        } catch (DuplicateTargetNameException e) {
            binding.rejectValue("name", "target.duplicate", e.getMessage());
            return FORM_VIEW;
        } catch (InvalidTargetException e) {
            rejectOnForm(binding, e);
            return FORM_VIEW;
        }
    }

    @GetMapping("/{id}/edit")
    String editForm(@PathVariable UUID id, Model model, RedirectAttributes flash) {
        DatabaseTarget target;
        try {
            target = service.get(id);
        } catch (NoSuchElementException e) {
            return targetGone(flash);
        }
        model.addAttribute("form", EditTargetForm.of(target));
        return editView(model, target);
    }

    @PostMapping("/{id}")
    String update(
            @PathVariable UUID id,
            @Valid @ModelAttribute("form") EditTargetForm form,
            BindingResult binding,
            Model model,
            RedirectAttributes flash) {

        // Read even when the form is invalid: the page shows the schema, which
        // is not a field the operator sends.
        DatabaseTarget current;
        try {
            current = service.get(id);
        } catch (NoSuchElementException e) {
            return targetGone(flash);
        }
        validateEditFields(current, form, binding);
        if (binding.hasErrors()) {
            return editView(model, current);
        }
        try {
            DatabaseTarget saved = service.edit(id, form.toCommand());
            flash.addFlashAttribute("message", "Saved target '%s'".formatted(saved.getName()));
            return "redirect:/databases";
        } catch (DuplicateTargetNameException e) {
            binding.rejectValue("name", "target.duplicate", e.getMessage());
        } catch (InvalidTargetException e) {
            rejectOnForm(binding, e);
        } catch (NoSuchElementException e) {
            return targetGone(flash);
        }
        return editView(model, current);
    }

    /**
     * Runs synchronously: the client is given an explicit connect timeout, so
     * this is bounded in a way a backup is not. Backups will need the
     * background treatment; a probe does not.
     */
    @PostMapping("/{id}/test")
    String test(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            ConnectionCheck check = connectionTest.test(id);
            if (check.successful()) {
                flash.addFlashAttribute("message", "Connection succeeded");
            } else {
                flash.addFlashAttribute("error", "Connection failed: " + check.message());
            }
        } catch (NoSuchElementException e) {
            flash.addFlashAttribute("error", "That target no longer exists");
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/databases";
    }

    /**
     * Returns as soon as the execution row exists, redirecting to its detail
     * page. The dump itself runs in the background, so a schema that takes
     * minutes does not hold the request open.
     */
    @PostMapping("/{id}/backup")
    String backUpNow(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            return "redirect:/executions/" + backups.start(id);
        } catch (NoSuchElementException e) {
            flash.addFlashAttribute("error", "That target no longer exists");
            return "redirect:/databases";
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/databases";
        }
    }

    /** What removing the target takes with it, said before the click rather than discovered after it. */
    @GetMapping("/{id}/delete")
    String confirmDelete(@PathVariable UUID id, Model model, RedirectAttributes flash) {
        try {
            model.addAttribute("preview", service.previewRemoval(id));
        } catch (NoSuchElementException e) {
            return targetGone(flash);
        }
        return "database/delete";
    }

    /**
     * A target with backups goes only with its name typed: its backups go with
     * it (ADR-015). Typing, not ticking, for the reason ADR-014 gives.
     */
    @PostMapping("/{id}/delete")
    String delete(
            @PathVariable UUID id,
            @RequestParam(value = "confirmation", required = false) String confirmation,
            RedirectAttributes flash) {

        DatabaseTarget target;
        try {
            target = service.get(id);
        } catch (NoSuchElementException e) {
            flash.addFlashAttribute("message", "Target removed");
            return "redirect:/databases";
        }
        boolean named = confirmation != null && target.getName().equals(confirmation.strip());
        try {
            service.delete(id, named);
            flash.addFlashAttribute("message", "Target '%s' removed".formatted(target.getName()));
            return "redirect:/databases";
        } catch (TargetInUseException e) {
            flash.addFlashAttribute("error", confirmation == null || confirmation.isBlank()
                    ? e.getMessage()
                    : "Type the target's name exactly — '%s' — to remove it with its backups"
                            .formatted(target.getName()));
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/databases/" + id + "/delete";
    }

    /** The repository returns at most one per target; a duplicate would be a bug there, so it fails here. */
    private static Map<UUID, BackupExecution> byTarget(List<BackupExecution> onePerTarget) {
        return onePerTarget.stream().collect(Collectors.toMap(BackupExecution::getTargetId, e -> e));
    }

    /** The shared form page, in edit mode: {@code editing} is what switches it. */
    private static String editView(Model model, DatabaseTarget target) {
        model.addAttribute("editing", target);
        return FORM_VIEW;
    }

    private static String targetGone(RedirectAttributes flash) {
        flash.addFlashAttribute("error", "That target no longer exists");
        return "redirect:/databases";
    }

    private static void rejectOnForm(BindingResult binding, InvalidTargetException e) {
        String formField = FORM_FIELDS.get(e.getField());
        if (formField == null) {
            binding.reject("target.invalid", e.getMessage());
        } else {
            binding.rejectValue(formField, "target.invalid", e.getMessage());
        }
    }

    private static void validateRegistrationFields(DatabaseTargetForm form, BindingResult binding) {
        if (form.getEngine() == null || form.getEngine().isFileBased()) {
            return;
        }
        ValidationUtils.rejectIfEmptyOrWhitespace(binding, "host", "target.required", "Host is required");
        if (form.getPort() == null) {
            binding.rejectValue("port", "target.required", "Port is required");
        }
        ValidationUtils.rejectIfEmptyOrWhitespace(binding, "username", "target.required", "Username is required");
        ValidationUtils.rejectIfEmptyOrWhitespace(binding, "password", "target.required", "Password is required");
        if (form.getEngine() == DatabaseEngine.MONGODB) {
            ValidationUtils.rejectIfEmptyOrWhitespace(
                    binding, "authenticationDatabase", "target.required", "Authentication database is required");
        }
        if (form.getEngine() == DatabaseEngine.ORACLE) {
            ValidationUtils.rejectIfEmptyOrWhitespace(
                    binding, "dataPumpDirectory", "target.required", "Data Pump directory is required");
        }
    }

    private static void validateEditFields(
            DatabaseTarget target, EditTargetForm form, BindingResult binding) {
        if (target.getEngine().isFileBased()) {
            return;
        }
        ValidationUtils.rejectIfEmptyOrWhitespace(binding, "host", "target.required", "Host is required");
        if (form.getPort() == null) {
            binding.rejectValue("port", "target.required", "Port is required");
        }
        ValidationUtils.rejectIfEmptyOrWhitespace(binding, "username", "target.required", "Username is required");
        if (target.getEngine() == DatabaseEngine.MONGODB) {
            ValidationUtils.rejectIfEmptyOrWhitespace(
                    binding, "authenticationDatabase", "target.required", "Authentication database is required");
        }
        if (target.getEngine() == DatabaseEngine.ORACLE) {
            ValidationUtils.rejectIfEmptyOrWhitespace(
                    binding, "dataPumpDirectory", "target.required", "Data Pump directory is required");
        }
    }
}
