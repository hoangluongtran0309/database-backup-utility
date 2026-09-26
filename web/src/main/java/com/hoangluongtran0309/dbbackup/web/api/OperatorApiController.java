package com.hoangluongtran0309.dbbackup.web.api;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService;
import com.hoangluongtran0309.dbbackup.application.backup.RunBackupService;
import com.hoangluongtran0309.dbbackup.application.notification.ManageNotificationChannelService;
import com.hoangluongtran0309.dbbackup.application.notification.ManageTargetNotificationService;
import com.hoangluongtran0309.dbbackup.application.notification.SaveNotificationChannelCommand;
import com.hoangluongtran0309.dbbackup.application.restore.RestoreBackupService;
import com.hoangluongtran0309.dbbackup.application.retention.ManageBackupRetentionService;
import com.hoangluongtran0309.dbbackup.application.retention.SaveBackupRetentionPolicyCommand;
import com.hoangluongtran0309.dbbackup.application.schedule.ManageBackupScheduleService;
import com.hoangluongtran0309.dbbackup.application.schedule.SaveBackupScheduleCommand;
import com.hoangluongtran0309.dbbackup.application.storage.ManageStorageProfileService;
import com.hoangluongtran0309.dbbackup.application.storage.SaveStorageProfileCommand;
import com.hoangluongtran0309.dbbackup.application.target.EditTargetCommand;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.application.target.RegisterTargetCommand;
import com.hoangluongtran0309.dbbackup.application.target.TestTargetConnectionService;
import com.hoangluongtran0309.dbbackup.application.verification.RestoreVerificationService;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;
import com.hoangluongtran0309.dbbackup.core.model.NotificationEventType;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProvider;
import com.hoangluongtran0309.dbbackup.core.model.TargetNotificationSubscription;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreVerificationExecutionRepository;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OperatorApiController {
    private final ManageDatabaseTargetService targets;
    private final TestTargetConnectionService targetConnections;
    private final ManageStorageProfileService storage;
    private final ManageNotificationChannelService channels;
    private final ManageTargetNotificationService subscriptions;
    private final ManageBackupScheduleService schedules;
    private final ManageBackupRetentionService retention;
    private final RunBackupService backupRunner;
    private final BackupExecutionRepository backups;
    private final BackupArtifactService artifacts;
    private final RestoreBackupService restoreRunner;
    private final RestoreExecutionRepository restores;
    private final RestoreVerificationService verificationRunner;
    private final RestoreVerificationExecutionRepository verifications;

    @GetMapping("/targets")
    ApiEnvelope<?> targets() { return ok(targets.listAll().stream().map(ApiModel::target).toList()); }

    @GetMapping("/targets/{id}")
    ApiEnvelope<?> target(@PathVariable UUID id) { return ok(ApiModel.target(targets.get(id))); }

    @PostMapping("/targets")
    ResponseEntity<ApiEnvelope<?>> addTarget(@RequestBody TargetRequest request) {
        var saved = targets.register(request.registerCommand());
        return ResponseEntity.status(201).body(ok(ApiModel.target(saved)));
    }

    @PutMapping("/targets/{id}")
    ApiEnvelope<?> updateTarget(@PathVariable UUID id, @RequestBody TargetRequest request) {
        return ok(ApiModel.target(targets.edit(id, request.editCommand())));
    }

    @DeleteMapping("/targets/{id}")
    ApiEnvelope<?> deleteTarget(@PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean confirmBackups) {
        targets.delete(id, confirmBackups);
        return ok(Map.of("deleted", true));
    }

    @PostMapping("/targets/{id}/test")
    ApiEnvelope<?> testTarget(@PathVariable UUID id) { return ok(targetConnections.test(id)); }

    @PostMapping("/targets/{id}/backups")
    ResponseEntity<ApiEnvelope<?>> runBackup(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(ok(Map.of("executionId", backupRunner.start(id))));
    }

    @GetMapping("/targets/{id}/notifications")
    ApiEnvelope<?> targetNotifications(@PathVariable UUID id) {
        targets.get(id);
        return ok(subscriptions.subscriptionsFor(id).stream().map(ApiModel::subscription).toList());
    }

    @PutMapping("/targets/{id}/notifications")
    ApiEnvelope<?> setTargetNotifications(@PathVariable UUID id, @RequestBody SubscriptionRequest request) {
        subscriptions.replace(id, request.toDomain());
        return targetNotifications(id);
    }

    @GetMapping("/storage-profiles")
    ApiEnvelope<?> storageProfiles() { return ok(storage.listAll().stream().map(ApiModel::storage).toList()); }

    @GetMapping("/storage-profiles/{id}")
    ApiEnvelope<?> storageProfile(@PathVariable UUID id) { return ok(ApiModel.storage(storage.get(id))); }

    @PostMapping("/storage-profiles")
    ResponseEntity<ApiEnvelope<?>> addStorage(@RequestBody StorageRequest request) {
        return ResponseEntity.status(201).body(ok(ApiModel.storage(storage.create(request.command()))));
    }

    @PutMapping("/storage-profiles/{id}")
    ApiEnvelope<?> updateStorage(@PathVariable UUID id, @RequestBody StorageRequest request) {
        return ok(ApiModel.storage(storage.edit(id, request.command())));
    }

    @DeleteMapping("/storage-profiles/{id}")
    ApiEnvelope<?> deleteStorage(@PathVariable UUID id) {
        storage.delete(id); return ok(Map.of("deleted", true));
    }

    @PostMapping("/storage-profiles/{id}/test")
    ApiEnvelope<?> testStorage(@PathVariable UUID id) { return ok(storage.test(id)); }

    @GetMapping("/notification-channels")
    ApiEnvelope<?> notificationChannels() { return ok(channels.listAll().stream().map(ApiModel::channel).toList()); }

    @GetMapping("/notification-channels/{id}")
    ApiEnvelope<?> notificationChannel(@PathVariable UUID id) { return ok(ApiModel.channel(channels.get(id))); }

    @PostMapping("/notification-channels")
    ResponseEntity<ApiEnvelope<?>> addChannel(@RequestBody NotificationRequest request) {
        return ResponseEntity.status(201).body(ok(ApiModel.channel(channels.create(request.command()))));
    }

    @PutMapping("/notification-channels/{id}")
    ApiEnvelope<?> updateChannel(@PathVariable UUID id, @RequestBody NotificationRequest request) {
        return ok(ApiModel.channel(channels.edit(id, request.command())));
    }

    @DeleteMapping("/notification-channels/{id}")
    ApiEnvelope<?> deleteChannel(@PathVariable UUID id) {
        channels.delete(id); return ok(Map.of("deleted", true));
    }

    @PostMapping("/notification-channels/{id}/test")
    ApiEnvelope<?> testChannel(@PathVariable UUID id) { return ok(channels.test(id)); }

    @GetMapping("/schedules")
    ApiEnvelope<?> schedules() { return ok(schedules.listAll().stream().map(ApiModel::schedule).toList()); }

    @GetMapping("/schedules/{id}")
    ApiEnvelope<?> schedule(@PathVariable UUID id) {
        var item = schedules.listAll().stream().filter(v -> v.schedule().getId().equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("No backup schedule with id " + id));
        return ok(ApiModel.schedule(item));
    }

    @PostMapping("/schedules")
    ResponseEntity<ApiEnvelope<?>> addSchedule(@RequestBody ScheduleRequest request) {
        var saved = schedules.create(request.command());
        return ResponseEntity.status(201).body(scheduleById(saved.getId()));
    }

    @PutMapping("/schedules/{id}")
    ApiEnvelope<?> updateSchedule(@PathVariable UUID id, @RequestBody ScheduleRequest request) {
        schedules.edit(id, request.command()); return schedule(id);
    }

    @DeleteMapping("/schedules/{id}")
    ApiEnvelope<?> deleteSchedule(@PathVariable UUID id) {
        schedules.delete(id); return ok(Map.of("deleted", true));
    }

    @GetMapping("/retention")
    ApiEnvelope<?> retention() { return ok(retention.listAll().stream().map(ApiModel::retention).toList()); }

    @GetMapping("/retention/{targetId}")
    ApiEnvelope<?> retention(@PathVariable UUID targetId) {
        var target = retention.getTarget(targetId);
        return ok(ApiModel.retention(new ManageBackupRetentionService.RetentionView(
                target, retention.findForTarget(targetId))));
    }

    @PutMapping("/retention/{targetId}")
    ApiEnvelope<?> setRetention(@PathVariable UUID targetId, @RequestBody RetentionRequest request) {
        retention.save(targetId, new SaveBackupRetentionPolicyCommand(request.keepSuccessful()));
        return retention(targetId);
    }

    @DeleteMapping("/retention/{targetId}")
    ApiEnvelope<?> disableRetention(@PathVariable UUID targetId) {
        retention.disable(targetId); return ok(Map.of("disabled", true));
    }

    @GetMapping("/backups")
    ApiEnvelope<?> backups(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        requirePage(page, pageSize);
        var history = backups.findNewestFirst(page, pageSize);
        return ok(Map.of("items", history.items().stream().map(ApiModel::backup).toList(),
                "page", history.page(), "hasOlder", history.hasOlder(), "hasNewer", history.hasNewer()));
    }

    @GetMapping("/backups/{id}")
    ApiEnvelope<?> backup(@PathVariable UUID id) {
        var backup = backups.findById(id).orElseThrow(() -> new NoSuchElementException("No backup execution with id " + id));
        return ok(Map.of("execution", ApiModel.backup(backup),
                "verifications", verificationRunner.history(id).stream().map(ApiModel::verification).toList()));
    }

    @PostMapping("/backups/{id}/checksum")
    ApiEnvelope<?> verifyChecksum(@PathVariable UUID id) { return ok(artifacts.verify(id)); }

    @PostMapping("/backups/{id}/verifications")
    ResponseEntity<ApiEnvelope<?>> verifyRestore(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(ok(Map.of("verificationId", verificationRunner.start(id))));
    }

    @DeleteMapping("/backups/{id}")
    ApiEnvelope<?> deleteBackup(@PathVariable UUID id) {
        return ok(Map.of("deleted", true, "artifactRemoved", artifacts.delete(id)));
    }

    @GetMapping("/backups/{id}/artifact")
    ResponseEntity<InputStreamResource> download(@PathVariable UUID id) {
        var download = artifacts.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.filename()).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(download.content()));
    }

    @GetMapping("/restores")
    ApiEnvelope<?> restores(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        requirePage(page, pageSize);
        var history = restores.findNewestFirst(page, pageSize);
        return ok(Map.of("items", history.items().stream().map(ApiModel::restore).toList(),
                "page", history.page(), "hasOlder", history.hasOlder(), "hasNewer", history.hasNewer()));
    }

    @GetMapping("/restores/{id}")
    ApiEnvelope<?> restore(@PathVariable UUID id) {
        return ok(ApiModel.restore(restores.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No restore execution with id " + id))));
    }

    @PostMapping("/restores")
    ResponseEntity<ApiEnvelope<?>> runRestore(@RequestBody RestoreRequest request) {
        var destination = targets.get(request.targetId());
        if (request.confirmation() == null || !destination.getName().equals(request.confirmation().strip())) {
            throw new IllegalArgumentException("Type the destination target name exactly to confirm the overwrite");
        }
        return ResponseEntity.accepted().body(ok(Map.of("restoreId",
                restoreRunner.start(request.backupExecutionId(), request.targetId()))));
    }

    @GetMapping("/verifications/{id}")
    ApiEnvelope<?> verification(@PathVariable UUID id) {
        return ok(ApiModel.verification(verifications.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No restore verification with id " + id))));
    }

    private ApiEnvelope<?> scheduleById(UUID id) {
        var item = schedules.listAll().stream().filter(v -> v.schedule().getId().equals(id)).findFirst().orElseThrow();
        return ok(ApiModel.schedule(item));
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("page must be >= 1 and pageSize 1..100");
    }

    private static <T> ApiEnvelope<T> ok(T value) { return ApiEnvelope.success(value); }

    public record TargetRequest(String name, DatabaseEngine engine, String host, Integer port, String database,
            String username, String password, String authenticationDatabase, String dataPumpDirectory,
            UUID storageProfileId, boolean verifyAfterBackup) {
        RegisterTargetCommand registerCommand() { return new RegisterTargetCommand(name, engine, host, port, database,
                username, password, authenticationDatabase, dataPumpDirectory, storageProfileId, verifyAfterBackup); }
        EditTargetCommand editCommand() { return new EditTargetCommand(name, host, port, username, password,
                authenticationDatabase, dataPumpDirectory, storageProfileId, verifyAfterBackup); }
    }

    public record StorageRequest(String name, StorageProvider provider, String endpoint, String region,
            String projectId, String accountName, String bucket, String keyPrefix, boolean pathStyle,
            StorageCredentialMode credentialMode, String accessKeyId, String secretAccessKey,
            String serviceAccountJson, String accountKey) {
        SaveStorageProfileCommand command() { return new SaveStorageProfileCommand(name, provider, endpoint, region,
                projectId, accountName, bucket, keyPrefix, pathStyle, credentialMode, accessKeyId,
                secretAccessKey, serviceAccountJson, accountKey); }
    }

    public record NotificationRequest(String name, NotificationChannelType type, String botToken,
            String chatId, String webhookUrl, String emailTo) {
        SaveNotificationChannelCommand command() {
            return new SaveNotificationChannelCommand(name, type, botToken, chatId, webhookUrl, emailTo);
        }
    }

    public record SubscriptionRequest(List<SubscriptionInput> subscriptions) {
        List<TargetNotificationSubscription> toDomain() {
            return subscriptions == null ? List.of() : subscriptions.stream().map(SubscriptionInput::toDomain).toList();
        }
    }
    public record SubscriptionInput(UUID channelId, Set<NotificationEventType> events) {
        TargetNotificationSubscription toDomain() { return new TargetNotificationSubscription(channelId, events); }
    }
    public record ScheduleRequest(String name, UUID targetId, String cronExpression, String zoneId, boolean enabled) {
        SaveBackupScheduleCommand command() { return new SaveBackupScheduleCommand(name, targetId, cronExpression, zoneId, enabled); }
    }
    public record RetentionRequest(int keepSuccessful) { }
    public record RestoreRequest(UUID backupExecutionId, UUID targetId, String confirmation) { }
}
