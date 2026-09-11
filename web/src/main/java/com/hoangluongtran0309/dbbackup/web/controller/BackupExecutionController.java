package com.hoangluongtran0309.dbbackup.web.controller;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService;
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
    private final BackupArtifactService artifacts;

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
     * Streams the artifact out as it is stored — still gzipped, so what lands
     * on the operator's machine is byte for byte what this tool would restore.
     */
    @GetMapping("/{id}/download")
    ResponseEntity<InputStreamResource> download(@PathVariable UUID id) {
        BackupArtifactService.ArtifactDownload download = artifacts.download(id);
        InputStream content = download.content();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        // The name is one this tool generated and sanitised, but
                        // it is built here rather than echoed, so a hand-edited
                        // row cannot inject a header.
                        .filename(download.filename())
                        .build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(content));
    }

    @GetMapping("/{id}/delete")
    String confirmDelete(@PathVariable UUID id, Model model) {
        model.addAttribute("preview", artifacts.previewDeletion(id));
        model.addAttribute("targetNames", targetNames());
        return "execution/delete";
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            boolean hadArtifact = artifacts.delete(id);
            flash.addFlashAttribute("message", hadArtifact
                    ? "Backup deleted, along with its artifact"
                    // A failed backup never wrote a file; saying one went
                    // with it would be untrue.
                    : "Backup record deleted — it had no artifact");
        } catch (NoSuchElementException e) {
            flash.addFlashAttribute("error", "That backup no longer exists");
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/executions";
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
