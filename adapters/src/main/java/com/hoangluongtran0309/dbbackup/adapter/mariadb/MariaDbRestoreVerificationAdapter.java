package com.hoangluongtran0309.dbbackup.adapter.mariadb;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.verification.AbstractDockerSqlVerificationAdapter;
import com.hoangluongtran0309.dbbackup.adapter.verification.DockerVerificationSupport;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

@Component
class MariaDbRestoreVerificationAdapter extends AbstractDockerSqlVerificationAdapter {
    MariaDbRestoreVerificationAdapter(DockerVerificationSupport docker,
            @Value("${dbbackup.verification.enabled:false}") boolean enabled,
            @Value("${dbbackup.verification.mariadb-image:mariadb:10.11}") String image,
            @Value("${dbbackup.restore.timeout}") Duration timeout) {
        super(docker, enabled, image, "MARIADB_ROOT_PASSWORD", "mariadb", timeout);
    }
    @Override public DatabaseEngine engine() { return DatabaseEngine.MARIADB; }
}
