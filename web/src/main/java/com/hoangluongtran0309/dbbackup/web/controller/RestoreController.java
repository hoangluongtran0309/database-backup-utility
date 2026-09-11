package com.hoangluongtran0309.dbbackup.web.controller;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.hoangluongtran0309.dbbackup.application.restore.RestoreBackupService;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;

import lombok.RequiredArgsConstructor;

@Controller
@RequestMapping("/restores")
@RequiredArgsConstructor
public class RestoreController {

    private final RestoreBackupService restoreService;
    private final RestoreExecutionRepository restores;
    private final BackupExecutionRepository backups;
    private final ManageDatabaseTargetService targets;

    @GetMapping
    String list(Model model) {
        model.addAttribute("restores", restores.findAllNewestFirst());
        model.addAttribute("backups", backupsById());
        model.addAttribute("targetNames", targetNames());
        return "restore/list";
    }

    /**
     * The confirmation step. Restoring overwrites a live schema, so it is not
     * something a single click should be able to do.
     */
    @GetMapping("/new")
    String confirmForm(@RequestParam("backup") UUID backupExecutionId, Model model, RedirectAttributes flash) {
        BackupExecution backup = backups.findById(backupExecutionId).orElse(null);
        if (backup == null) {
            flash.addFlashAttribute("error", "That backup no longer exists");
            return "redirect:/executions";
        }
        DatabaseTarget target = targets.listAll().stream()
                .filter(t -> t.getId().equals(backup.getTargetId()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            flash.addFlashAttribute("error", "The target this backup came from no longer exists");
            return "redirect:/executions/" + backupExecutionId;
        }
        model.addAttribute("backup", backup);
        model.addAttribute("target", target);
        return "restore/confirm";
    }

    @PostMapping
    String start(
            @RequestParam("backup") UUID backupExecutionId,
            @RequestParam(value = "confirmation", required = false) String confirmation,
            RedirectAttributes flash) {

        BackupExecution backup = backups.findById(backupExecutionId).orElse(null);
        if (backup == null) {
            flash.addFlashAttribute("error", "That backup no longer exists");
            return "redirect:/executions";
        }

        // Typing the target's name, not ticking a box: it forces the operator
        // to read which schema is about to be overwritten.
        String expected = targets.listAll().stream()
                .filter(t -> t.getId().equals(backup.getTargetId()))
                .map(DatabaseTarget::getName)
                .findFirst()
                .orElse(null);
        if (expected == null) {
            flash.addFlashAttribute("error", "The target this backup came from no longer exists");
            return "redirect:/executions/" + backupExecutionId;
        }
        if (confirmation == null || !expected.equals(confirmation.strip())) {
            flash.addFlashAttribute("error",
                    "Type the target's name exactly — '%s' — to confirm the overwrite".formatted(expected));
            return "redirect:/restores/new?backup=" + backupExecutionId;
        }

        try {
            return "redirect:/restores/" + restoreService.start(backupExecutionId);
        } catch (NoSuchElementException | RestoreFailedException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/executions/" + backupExecutionId;
        }
    }

    /**
     * A restore that is not there sends the operator back to the restore list,
     * not to the backup list {@code ExecutionErrorHandler} would pick: the list
     * they came from is the one that answers "what happened to it?".
     */
    @GetMapping("/{id}")
    String detail(@PathVariable UUID id, Model model, RedirectAttributes flash) {
        RestoreExecution restore = restores.findById(id).orElse(null);
        if (restore == null) {
            flash.addFlashAttribute("error", "That restore no longer exists");
            return "redirect:/restores";
        }
        BackupExecution backup = backups.findById(restore.getBackupExecutionId()).orElse(null);

        model.addAttribute("restore", restore);
        model.addAttribute("backup", backup);
        model.addAttribute("targetName",
                backup == null ? null : targetNames().get(backup.getTargetId()));
        return "restore/detail";
    }

    private Map<UUID, BackupExecution> backupsById() {
        return backups.findAllNewestFirst().stream()
                .collect(Collectors.toMap(BackupExecution::getId, b -> b));
    }

    private Map<UUID, String> targetNames() {
        List<DatabaseTarget> all = targets.listAll();
        return all.stream().collect(Collectors.toMap(DatabaseTarget::getId, DatabaseTarget::getName));
    }
}
