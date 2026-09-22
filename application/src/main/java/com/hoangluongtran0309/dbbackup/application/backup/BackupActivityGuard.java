package com.hoangluongtran0309.dbbackup.application.backup;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * Serializes the small acceptance/deletion critical sections for one backup.
 * Fixed stripes avoid retaining one lock forever for every historical UUID.
 */
@Component
public class BackupActivityGuard {

    private static final int STRIPES = 64;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public BackupActivityGuard() {
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public <T> T withBackup(UUID backupId, Supplier<T> action) {
        ReentrantLock lock = locks[Math.floorMod(backupId.hashCode(), locks.length)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
