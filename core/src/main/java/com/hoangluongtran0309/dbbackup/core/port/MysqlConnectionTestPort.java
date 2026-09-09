package com.hoangluongtran0309.dbbackup.core.port;

import com.hoangluongtran0309.dbbackup.core.model.MysqlConnection;

/**
 * Probes whether a target is actually reachable with the credentials held for
 * it.
 */
public interface MysqlConnectionTestPort {

    /**
     * Never throws for an unreachable target: a refused connection is an
     * answer, not a failure of the probe, and the operator needs to see the
     * reason rather than a stack trace.
     *
     * @return whether the target answered, and what it said if it did not
     */
    Result test(MysqlConnection connection);

    /** @param message MySQL's own words on failure, empty on success */
    record Result(boolean successful, String message) {

        public static Result ok() {
            return new Result(true, "");
        }

        public static Result failed(String message) {
            return new Result(false, message);
        }
    }
}
