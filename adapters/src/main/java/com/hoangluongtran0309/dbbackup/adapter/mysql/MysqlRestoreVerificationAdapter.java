package com.hoangluongtran0309.dbbackup.adapter.mysql;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.verification.AbstractDockerSqlVerificationAdapter;
import com.hoangluongtran0309.dbbackup.adapter.verification.DockerVerificationSupport;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

@Component
class MysqlRestoreVerificationAdapter extends AbstractDockerSqlVerificationAdapter {
    MysqlRestoreVerificationAdapter(DockerVerificationSupport docker,
            @Value("${dbbackup.verification.enabled:false}") boolean enabled,
            @Value("${dbbackup.verification.mysql-image:mysql:8.4}") String image,
            @Value("${dbbackup.restore.timeout}") Duration timeout) {
        super(docker, enabled, image, "MYSQL_ROOT_PASSWORD", "mysql", timeout);
    }
    @Override public DatabaseEngine engine() { return DatabaseEngine.MYSQL; }
}
