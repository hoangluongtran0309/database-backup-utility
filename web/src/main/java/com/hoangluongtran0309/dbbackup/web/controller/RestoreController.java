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
import com.hoangluongtran0309.dbbackup.core.port.HistoryPage;
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
    String list(@RequestParam(name = "page", defaultValue = "1") int page, Model model) {
        HistoryPage<RestoreExecution> history =
                restores.findNewestFirst(HistoryPaging.page(page), HistoryPaging.PAGE_SIZE);
        model.addAttribute("history", history);
        model.addAttribute("restores", history.items());
        model.addAttribute("backups", backupsOf(history.items()));
        model.addAttribute("targetNames", targetNames());
        return "restore/list";
    }

    /**
     * The confirmation step. Restoring overwrites a live schema, so it is not
     * something a single click should be able to do.
     *
     * <p>The destination defaults to the target the backup came from; any
     * other registered target can be chosen instead (ADR-014). Choosing one
     * reloads this page, so the warning and the name to type always describe
     * the target that will actually be overwritten.
     */
    @GetMapping("/new")
    String confirmForm(
            @RequestParam("backup") UUID backupExecutionId,
            @RequestParam(value = "target", required = false) UUID targetId,
            Model model,
            RedirectAttributes flash) {

        BackupExecution backup = backups.findById(backupExecutionId).orElse(null);
        if (backup == null) {
            flash.addFlashAttribute("error", "That backup no longer exists");
            return "redirect:/executions";
        }
        List<DatabaseTarget> all = targets.listAll();
        DatabaseTarget source = byId(all, backup.getTargetId());
        if (source == null) {
            flash.addFlashAttribute("error", "The target this backup came from no longer exists");
            return "redirect:/executions/" + backupExecutionId;
        }
        DatabaseTarget requested = byId(all, targetId != null ? targetId : backup.getTargetId());
        if (requested != null && requested.getEngine() != source.getEngine()) {
            flash.addFlashAttribute("error",
                    "Choose a %s target for this backup".formatted(source.getEngine().displayName()));
            return "redirect:/restores/new?backup=" + backupExecutionId;
        }
        List<DatabaseTarget> compatible = all.stream()
                .filter(candidate -> candidate.getEngine() == source.getEngine())
                .toList();
        DatabaseTarget destination = byId(compatible, targetId != null ? targetId : backup.getTargetId());
        if (destination == null && targetId != null) {
            flash.addFlashAttribute("error", "That target no longer exists");
            return "redirect:/restores/new?backup=" + backupExecutionId;
        }
        model.addAttribute("backup", backup);
        model.addAttribute("source", source);
        model.addAttribute("target", destination);
        model.addAttribute("targets", compatible);
        model.addAttribute("engineAvailable", targets.supports(source.getEngine()));
        return "restore/confirm";
    }

    @PostMapping
    String start(
            @RequestParam("backup") UUID backupExecutionId,
            @RequestParam(value = "target", required = false) UUID targetId,
            @RequestParam(value = "confirmation", required = false) String confirmation,
            RedirectAttributes flash) {

        BackupExecution backup = backups.findById(backupExecutionId).orElse(null);
        if (backup == null) {
            flash.addFlashAttribute("error", "That backup no longer exists");
            return "redirect:/executions";
        }
        UUID destinationId = targetId != null ? targetId : backup.getTargetId();
        String back = "redirect:/restores/new?backup=" + backupExecutionId
                + (targetId != null ? "&target=" + targetId : "");

        // Typing the destination's name, not ticking a box: it forces the
        // operator to read which schema is about to be overwritten — and with
        // a choice of targets, that it is the one they meant.
        DatabaseTarget destination = byId(targets.listAll(), destinationId);
        if (destination == null) {
            flash.addFlashAttribute("error", "The target to restore into no longer exists");
            return "redirect:/restores/new?backup=" + backupExecutionId;
        }
        String expected = destination.getName();
        if (confirmation == null || !expected.equals(confirmation.strip())) {
            flash.addFlashAttribute("error",
                    "Type the target's name exactly — '%s' — to confirm the overwrite".formatted(expected));
            return back;
        }

        try {
            return "redirect:/restores/" + restoreService.start(backupExecutionId, destinationId);
        } catch (NoSuchElementException | RestoreFailedException | IllegalStateException e) {
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

        Map<UUID, String> names = targetNames();
        model.addAttribute("restore", restore);
        model.addAttribute("backup", backup);
        model.addAttribute("targetName", names.get(restore.getTargetId()));
        model.addAttribute("sourceName", backup == null ? null : names.get(backup.getTargetId()));
        return "restore/detail";
    }

    /** Only the backups this page refers to, not the whole history. */
    private Map<UUID, BackupExecution> backupsOf(List<RestoreExecution> page) {
        return backups.findAllById(page.stream().map(RestoreExecution::getBackupExecutionId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(BackupExecution::getId, b -> b));
    }

    private static DatabaseTarget byId(List<DatabaseTarget> all, UUID id) {
        return all.stream().filter(t -> t.getId().equals(id)).findFirst().orElse(null);
    }

    private Map<UUID, String> targetNames() {
        List<DatabaseTarget> all = targets.listAll();
        return all.stream().collect(Collectors.toMap(DatabaseTarget::getId, DatabaseTarget::getName));
    }
}
