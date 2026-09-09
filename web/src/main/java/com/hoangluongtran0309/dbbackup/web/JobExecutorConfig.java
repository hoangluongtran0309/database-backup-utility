package com.hoangluongtran0309.dbbackup.web;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The pool backups and restores run on.
 *
 * <p>One pool for both, so the bound is on total heavy work rather than on each
 * kind separately — a machine busy restoring is not also free to run four
 * dumps.
 *
 * <p>Bounded on both axes on purpose. Each running job is a child process
 * competing for the same disk, so an unbounded pool turns a handful of
 * impatient clicks into a server that is slower at everything. A bounded queue means the pool refuses work instead of growing
 * without limit, and {@code RunBackupService} turns that refusal into a visible
 * failed execution rather than a silently dropped job.
 *
 * <p>This is not a scheduler. Nothing here decides <em>when</em> a backup
 * happens; it only decides how many may happen at once.
 *
 * <p>This class deliberately depends on nothing. {@code RunBackupService}
 * consumes the bean defined here, so injecting that service into this
 * configuration would be a bean cycle — the startup repair that needs it lives
 * in {@link InterruptedBackupRepair} instead.
 */
@Configuration
class JobExecutorConfig {

    @Bean
    Executor jobExecutor(
            @Value("${dbbackup.jobs.concurrency}") int concurrency,
            @Value("${dbbackup.jobs.queue-capacity}") int queueCapacity) {

        return new ThreadPoolExecutor(
                concurrency, concurrency,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("dbbackup-job-" + thread.threadId());
                    // Daemon: shutdown is not held up by a long job. The row it
                    // leaves behind is repaired at the next startup.
                    thread.setDaemon(true);
                    return thread;
                });
        // Default AbortPolicy: throws RejectedExecutionException, which the
        // service catches and records. CallerRunsPolicy would instead run the
        // dump on the HTTP thread and hang the request for minutes.
    }
}
