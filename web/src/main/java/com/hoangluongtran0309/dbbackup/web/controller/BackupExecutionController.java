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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.application.storage.ManageStorageProfileService;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.HistoryPage;

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
    private final ManageStorageProfileService storageProfiles;

    @GetMapping
    String list(@RequestParam(name = "page", defaultValue = "1") int page, Model model) {
        HistoryPage<BackupExecution> history =
                executions.findNewestFirst(HistoryPaging.page(page), HistoryPaging.PAGE_SIZE);
        model.addAttribute("history", history);
        model.addAttribute("executions", history.items());
        model.addAttribute("targetNames", targetNames());
        return "execution/list";
    }

    @GetMapping("/{id}")
    String detail(@PathVariable UUID id, Model model) {
        BackupExecution execution = executions.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No backup execution with id " + id));
        DatabaseTarget target = targets.listAll().stream()
                .filter(candidate -> candidate.getId().equals(execution.getTargetId()))
                .findFirst()
                .orElse(null);
        model.addAttribute("execution", execution);
        model.addAttribute("targetName", target == null ? null : target.getName());
        model.addAttribute("targetEngine", target == null ? null : target.getEngine());
        model.addAttribute("artifactOnDisk", artifacts.isOnDisk(execution));
        model.addAttribute("storageProfileName", execution.getStorageProfileId() == null
                ? "Local filesystem"
                : storageProfiles.get(execution.getStorageProfileId()).getName());
        return "execution/detail";
    }

    /**
     * Re-reads the artifact and compares it with the checksum recorded when it
     * was written. Synchronous: it reads the file once, which is bounded by the
     * disk in a way a dump is not.
     */
    @PostMapping("/{id}/verify")
    String verify(@PathVariable UUID id, RedirectAttributes flash) {
        BackupArtifactService.Verification result = artifacts.verify(id);
        switch (result.integrity()) {
            case INTACT -> flash.addFlashAttribute("message",
                    "Verified: the artifact matches the checksum recorded when it was made");
            case MISMATCH -> flash.addFlashAttribute("error",
                    "The artifact does not match its checksum — recorded %s, now %s. It has changed since it "
                            .formatted(result.recorded(), result.actual())
                            + "was written, and a restore from it will be refused.");
            case MISSING -> flash.addFlashAttribute("error", "The artifact is no longer available");
            case NOT_RECORDED -> flash.addFlashAttribute("message",
                    "This backup predates checksums, so there is nothing to compare with. Its SHA-256 now is "
                            + result.actual());
        }
        return "redirect:/executions/" + id;
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

    /**
     * The backups ticked on the list, and what deleting them takes with them.
     * A GET, so the list's checkboxes need no script: the list page is a form
     * that lands here.
     */
    @GetMapping("/delete")
    String confirmDeleteMany(
            @RequestParam(name = "id", required = false) List<UUID> ids,
            Model model,
            RedirectAttributes flash) {

        if (ids == null || ids.isEmpty()) {
            flash.addFlashAttribute("error", "Tick the backups to delete first");
            return "redirect:/executions";
        }
        BackupArtifactService.BulkDeletionPreview preview = artifacts.previewDeletions(ids);
        if (preview.executions().isEmpty()) {
            flash.addFlashAttribute("error", "Those backups no longer exist");
            return "redirect:/executions";
        }
        model.addAttribute("preview", preview);
        model.addAttribute("targetNames", targetNames());
        return "execution/delete-many";
    }

    @PostMapping("/delete")
    String deleteMany(
            @RequestParam(name = "id", required = false) List<UUID> ids,
            RedirectAttributes flash) {

        if (ids == null || ids.isEmpty()) {
            flash.addFlashAttribute("error", "Tick the backups to delete first");
            return "redirect:/executions";
        }
        try {
            BackupArtifactService.BulkDeletion done = artifacts.deleteAll(ids);
            flash.addFlashAttribute("message", summary(done));
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/executions";
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

    /** Counted in the singular when there is one, like every other count in the console. */
    private static String summary(BackupArtifactService.BulkDeletion done) {
        String text = done.deleted() == 1
                ? "1 backup deleted"
                : "%d backups deleted".formatted(done.deleted());
        if (done.artifactsRemoved() > 0) {
            text += done.artifactsRemoved() == 1
                    ? ", along with 1 artifact"
                    : ", along with %d artifacts".formatted(done.artifactsRemoved());
        }
        if (done.alreadyGone() > 0) {
            text += done.alreadyGone() == 1
                    ? " — 1 was already gone"
                    : " — %d were already gone".formatted(done.alreadyGone());
        }
        return text;
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
