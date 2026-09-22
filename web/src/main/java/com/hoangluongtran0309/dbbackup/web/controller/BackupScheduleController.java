package com.hoangluongtran0309.dbbackup.web.controller;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.hoangluongtran0309.dbbackup.application.schedule.ManageBackupScheduleService;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.core.exception.DuplicateScheduleNameException;
import com.hoangluongtran0309.dbbackup.core.exception.InvalidScheduleException;
import com.hoangluongtran0309.dbbackup.core.model.BackupSchedule;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.web.dto.BackupScheduleForm;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Controller
@RequestMapping("/schedules")
@RequiredArgsConstructor
public class BackupScheduleController {

    private static final String FORM_VIEW = "schedule/form";
    private static final Map<String, String> FORM_FIELDS = Map.of(
            "name", "name",
            "targetId", "targetId",
            "cronExpression", "cronExpression",
            "zoneId", "zoneId");

    private final ManageBackupScheduleService schedules;
    private final ManageDatabaseTargetService targets;

    @GetMapping
    String list(Model model) {
        model.addAttribute("schedules", schedules.listAll());
        model.addAttribute("targetNames", targetNames());
        return "schedule/list";
    }

    @GetMapping("/new")
    String newForm(@RequestParam(name = "target", required = false) UUID targetId, Model model) {
        model.addAttribute("form", BackupScheduleForm.blank(targetId));
        return form(model, null);
    }

    @PostMapping
    String create(
            @Valid @ModelAttribute("form") BackupScheduleForm form,
            BindingResult binding,
            Model model,
            RedirectAttributes flash) {

        if (binding.hasErrors()) {
            return form(model, null);
        }
        try {
            BackupSchedule saved = schedules.create(form.toCommand());
            flash.addFlashAttribute("message", "Created schedule '%s'".formatted(saved.getName()));
            return "redirect:/schedules";
        } catch (DuplicateScheduleNameException e) {
            binding.rejectValue("name", "schedule.duplicate", e.getMessage());
        } catch (InvalidScheduleException e) {
            reject(binding, e);
        } catch (IllegalStateException e) {
            binding.reject("schedule.unavailable", e.getMessage());
        }
        return form(model, null);
    }

    @GetMapping("/{id}/edit")
    String editForm(@PathVariable UUID id, Model model, RedirectAttributes flash) {
        try {
            BackupSchedule schedule = schedules.get(id);
            model.addAttribute("form", BackupScheduleForm.of(schedule));
            return form(model, schedule);
        } catch (NoSuchElementException e) {
            return gone(flash);
        }
    }

    @PostMapping("/{id}")
    String update(
            @PathVariable UUID id,
            @Valid @ModelAttribute("form") BackupScheduleForm form,
            BindingResult binding,
            Model model,
            RedirectAttributes flash) {

        BackupSchedule current;
        try {
            current = schedules.get(id);
        } catch (NoSuchElementException e) {
            return gone(flash);
        }
        if (binding.hasErrors()) {
            return form(model, current);
        }
        try {
            BackupSchedule saved = schedules.edit(id, form.toCommand());
            flash.addFlashAttribute("message", "Saved schedule '%s'".formatted(saved.getName()));
            return "redirect:/schedules";
        } catch (DuplicateScheduleNameException e) {
            binding.rejectValue("name", "schedule.duplicate", e.getMessage());
        } catch (InvalidScheduleException e) {
            reject(binding, e);
        } catch (IllegalStateException e) {
            binding.reject("schedule.unavailable", e.getMessage());
        }
        return form(model, current);
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            BackupSchedule schedule = schedules.get(id);
            schedules.delete(id);
            flash.addFlashAttribute("message", "Deleted schedule '%s'".formatted(schedule.getName()));
        } catch (NoSuchElementException e) {
            flash.addFlashAttribute("message", "Schedule already deleted");
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/schedules";
    }

    private String form(Model model, BackupSchedule editing) {
        model.addAttribute("targets", targets.listAll());
        model.addAttribute("editing", editing);
        return FORM_VIEW;
    }

    private Map<UUID, String> targetNames() {
        return targets.listAll().stream()
                .collect(Collectors.toMap(DatabaseTarget::getId, DatabaseTarget::getName));
    }

    private static void reject(BindingResult binding, InvalidScheduleException e) {
        String field = FORM_FIELDS.get(e.getField());
        if (field == null) {
            binding.reject("schedule.invalid", e.getMessage());
        } else {
            binding.rejectValue(field, "schedule.invalid", e.getMessage());
        }
    }

    private static String gone(RedirectAttributes flash) {
        flash.addFlashAttribute("error", "That schedule no longer exists");
        return "redirect:/schedules";
    }
}
