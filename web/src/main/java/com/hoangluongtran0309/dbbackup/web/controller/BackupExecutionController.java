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
import org.springframework.web.bind.annotation.RequestMapping;

import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Reading backup history. Starting a backup lives on the target's own resource,
 * in {@link DatabaseTargetController}.
 */
@Controller
@RequestMapping("/executions")
@RequiredArgsConstructor
public class BackupExecutionController {

    private final BackupExecutionRepository executions;
    private final ManageDatabaseTargetService targets;

    @GetMapping
    String list(Model model) {
        List<BackupExecution> all = executions.findAllNewestFirst();
        model.addAttribute("executions", all);
        model.addAttribute("targetNames", targetNames());
        return "execution/list";
    }

    @GetMapping("/{id}")
    String detail(@PathVariable UUID id, Model model) {
        BackupExecution execution = executions.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No backup execution with id " + id));
        model.addAttribute("execution", execution);
        model.addAttribute("targetName", targetNames().get(execution.getTargetId()));
        return "execution/detail";
    }

    /**
     * Resolved for display only. The execution stores the target's id, not its
     * name, so renaming a target does not rewrite history.
     */
    private Map<UUID, String> targetNames() {
        return targets.listAll().stream()
                .collect(Collectors.toMap(DatabaseTarget::getId, DatabaseTarget::getName));
    }
}
