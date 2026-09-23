package com.hoangluongtran0309.dbbackup.web.controller;

import java.util.NoSuchElementException;
import java.util.UUID;

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

import com.hoangluongtran0309.dbbackup.application.storage.ManageStorageProfileService;
import com.hoangluongtran0309.dbbackup.core.exception.DuplicateStorageProfileNameException;
import com.hoangluongtran0309.dbbackup.core.exception.StorageProfileInUseException;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;
import com.hoangluongtran0309.dbbackup.core.model.StorageProvider;
import com.hoangluongtran0309.dbbackup.web.dto.StorageProfileForm;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Controller
@RequestMapping("/storage")
@RequiredArgsConstructor
public class StorageProfileController {
    private final ManageStorageProfileService service;

    @GetMapping String list(Model model) {
        model.addAttribute("profiles", service.listAll());
        return "storage/list";
    }

    @GetMapping("/new") String newForm(
            @RequestParam(defaultValue = "S3") StorageProvider provider, Model model) {
        model.addAttribute("form", StorageProfileForm.blank(provider));
        return form(model, null, provider);
    }

    @PostMapping String create(@Valid @ModelAttribute("form") StorageProfileForm form,
            BindingResult binding, Model model, RedirectAttributes flash) {
        if (binding.hasErrors()) {
            clearSecrets(form);
            return form(model, null, providerOf(form));
        }
        try {
            StorageProfile saved = service.create(form.toCommand());
            flash.addFlashAttribute("message", "Created storage profile '" + saved.getName() + "'");
            return "redirect:/storage";
        } catch (DuplicateStorageProfileNameException e) {
            binding.rejectValue("name", "storage.duplicate", e.getMessage());
        } catch (IllegalArgumentException e) {
            binding.reject("storage.invalid", e.getMessage());
        }
        clearSecrets(form);
        return form(model, null, providerOf(form));
    }

    @GetMapping("/{id}/edit") String edit(@PathVariable UUID id, Model model, RedirectAttributes flash) {
        try {
            StorageProfile profile = service.get(id);
            model.addAttribute("editing", profile);
            model.addAttribute("form", StorageProfileForm.of(profile));
            return form(model, profile, profile.getProvider());
        } catch (NoSuchElementException e) {
            flash.addFlashAttribute("error", "That storage profile no longer exists");
            return "redirect:/storage";
        }
    }

    @PostMapping("/{id}") String update(@PathVariable UUID id,
            @Valid @ModelAttribute("form") StorageProfileForm form, BindingResult binding,
            Model model, RedirectAttributes flash) {
        StorageProfile current;
        try { current = service.get(id); }
        catch (NoSuchElementException e) { return "redirect:/storage"; }
        if (!binding.hasErrors()) {
            try {
                service.edit(id, form.toCommand());
                flash.addFlashAttribute("message", "Saved storage profile '" + form.getName() + "'");
                return "redirect:/storage";
            } catch (DuplicateStorageProfileNameException e) {
                binding.rejectValue("name", "storage.duplicate", e.getMessage());
            } catch (IllegalArgumentException | IllegalStateException e) {
                binding.reject("storage.invalid", e.getMessage());
            }
        }
        clearSecrets(form);
        model.addAttribute("editing", current);
        return form(model, current, current.getProvider());
    }

    @PostMapping("/{id}/test") String test(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            ConnectionCheck check = service.test(id);
            if (check.successful()) flash.addFlashAttribute("message", "Storage connection succeeded");
            else flash.addFlashAttribute("error", "Storage connection failed: " + check.message());
        } catch (NoSuchElementException e) {
            flash.addFlashAttribute("error", "That storage profile no longer exists");
        }
        return "redirect:/storage";
    }

    @PostMapping("/{id}/delete") String delete(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            service.delete(id);
            flash.addFlashAttribute("message", "Storage profile removed");
        } catch (StorageProfileInUseException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/storage";
    }

    private String form(Model model, StorageProfile profile, StorageProvider provider) {
        if (profile != null) model.addAttribute("editing", profile);
        model.addAttribute("provider", provider);
        model.addAttribute("credentialModes", provider == StorageProvider.S3
                ? new StorageCredentialMode[] {
                    StorageCredentialMode.STATIC, StorageCredentialMode.DEFAULT_CHAIN }
                : new StorageCredentialMode[] {
                    StorageCredentialMode.APPLICATION_DEFAULT, StorageCredentialMode.SERVICE_ACCOUNT_JSON });
        return "storage/form";
    }

    private static StorageProvider providerOf(StorageProfileForm form) {
        return form.getProvider() == null ? StorageProvider.S3 : form.getProvider();
    }

    private static void clearSecrets(StorageProfileForm form) {
        form.setSecretAccessKey(null);
        form.setServiceAccountJson(null);
    }
}
