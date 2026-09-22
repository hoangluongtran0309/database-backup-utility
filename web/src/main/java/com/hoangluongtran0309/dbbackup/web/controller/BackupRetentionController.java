package com.hoangluongtran0309.dbbackup.web.controller;

import java.util.NoSuchElementException;
import java.util.Optional;
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

import com.hoangluongtran0309.dbbackup.application.retention.ManageBackupRetentionService;
import com.hoangluongtran0309.dbbackup.core.exception.InvalidRetentionPolicyException;
import com.hoangluongtran0309.dbbackup.core.model.BackupRetentionPolicy;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.web.dto.BackupRetentionPolicyForm;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Controller
@RequestMapping("/retention")
@RequiredArgsConstructor
public class BackupRetentionController {

    private static final String FORM_VIEW = "retention/form";

    private final ManageBackupRetentionService retention;

    @GetMapping
    String list(Model model) {
        model.addAttribute("retention", retention.listAll());
        return "retention/list";
    }

    @GetMapping("/{targetId}/edit")
    String edit(@PathVariable UUID targetId, Model model, RedirectAttributes flash) {
        try {
            DatabaseTarget target = retention.getTarget(targetId);
            Optional<BackupRetentionPolicy> policy = retention.findForTarget(targetId);
            model.addAttribute("target", target);
            model.addAttribute("policy", policy.orElse(null));
            model.addAttribute("form", policy.map(BackupRetentionPolicyForm::of)
                    .orElseGet(BackupRetentionPolicyForm::blank));
            return FORM_VIEW;
        } catch (NoSuchElementException e) {
            return targetGone(flash);
        }
    }

    @PostMapping("/{targetId}")
    String save(
            @PathVariable UUID targetId,
            @Valid @ModelAttribute("form") BackupRetentionPolicyForm form,
            BindingResult binding,
            Model model,
            RedirectAttributes flash) {

        DatabaseTarget target;
        try {
            target = retention.getTarget(targetId);
        } catch (NoSuchElementException e) {
            return targetGone(flash);
        }
        if (binding.hasErrors()) {
            return formView(model, target, retention.findForTarget(targetId).orElse(null));
        }
        try {
            retention.save(targetId, form.toCommand());
            flash.addFlashAttribute("message", "Retention enabled for '%s'".formatted(target.getName()));
            return "redirect:/retention";
        } catch (InvalidRetentionPolicyException e) {
            if ("keepSuccessful".equals(e.getField())) {
                binding.rejectValue("keepSuccessful", "retention.invalid", e.getMessage());
            } else {
                binding.reject("retention.invalid", e.getMessage());
            }
            return formView(model, target, retention.findForTarget(targetId).orElse(null));
        }
    }

    @PostMapping("/{targetId}/disable")
    String disable(@PathVariable UUID targetId, RedirectAttributes flash) {
        try {
            DatabaseTarget target = retention.getTarget(targetId);
            retention.disable(targetId);
            flash.addFlashAttribute("message", "Retention disabled for '%s'".formatted(target.getName()));
        } catch (NoSuchElementException e) {
            flash.addFlashAttribute("message", "Target no longer exists");
        }
        return "redirect:/retention";
    }

    private static String formView(Model model, DatabaseTarget target, BackupRetentionPolicy policy) {
        model.addAttribute("target", target);
        model.addAttribute("policy", policy);
        return FORM_VIEW;
    }

    private static String targetGone(RedirectAttributes flash) {
        flash.addFlashAttribute("error", "That target no longer exists");
        return "redirect:/retention";
    }
}
