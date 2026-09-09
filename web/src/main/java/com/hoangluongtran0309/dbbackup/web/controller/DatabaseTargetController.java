package com.hoangluongtran0309.dbbackup.web.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException;
import com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.web.dto.DatabaseTargetForm;

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
            "host", "host",
            "port", "port",
            "databaseName", "database",
            "username", "username",
            "password", "password");

    private final ManageDatabaseTargetService service;

    @GetMapping
    String list(Model model) {
        model.addAttribute("targets", service.listAll());
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

    @PostMapping("/{id}/delete")
    String delete(@PathVariable UUID id, RedirectAttributes flash) {
        service.delete(id);
        flash.addFlashAttribute("message", "Target removed");
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
}
