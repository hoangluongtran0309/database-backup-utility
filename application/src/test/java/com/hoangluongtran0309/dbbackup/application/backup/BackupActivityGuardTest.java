package com.hoangluongtran0309.dbbackup.application.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class BackupActivityGuardTest {

    @Test
    void serializesActionsForTheSameBackup() throws Exception {
        BackupActivityGuard guard = new BackupActivityGuard();
        UUID backupId = UUID.randomUUID();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> guard.withBackup(backupId, () -> {
            firstEntered.countDown();
            await(releaseFirst);
            return null;
        }));
        assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> guard.withBackup(backupId, () -> {
            secondEntered.countDown();
            return null;
        }));

        assertThat(secondEntered.await(100, TimeUnit.MILLISECONDS)).isFalse();
        releaseFirst.countDown();
        CompletableFuture.allOf(first, second).get();
        assertThat(secondEntered.getCount()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
