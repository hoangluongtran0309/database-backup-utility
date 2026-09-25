package com.hoangluongtran0309.dbbackup.adapter.oracle;

import java.util.ArrayList;
import java.util.List;

/** Pure Data Pump parameter builder shared by normal and isolated restores. */
final class OracleDataPumpImportCommand {

    private OracleDataPumpImportCommand() {
    }

    static List<String> preflight(
            String directory,
            String dumpFile,
            String sourceSchema,
            String destinationSchema,
            String jobName,
            String sqlFile,
            boolean alwaysRemap) {
        List<String> command = base(directory, dumpFile, sourceSchema, jobName);
        addSchemaRemap(command, sourceSchema, destinationSchema, alwaysRemap);
        command.add("SQLFILE=" + sqlFile);
        return List.copyOf(command);
    }

    static List<String> importDump(
            String directory,
            String dumpFile,
            String sourceSchema,
            String destinationSchema,
            String jobName,
            boolean alwaysRemap) {
        List<String> command = base(directory, dumpFile, sourceSchema, jobName);
        command.add("TABLE_EXISTS_ACTION=REPLACE");
        command.add("TRANSFORM=OID:N");
        command.add("TRANSFORM=SEGMENT_ATTRIBUTES:N");
        addSchemaRemap(command, sourceSchema, destinationSchema, alwaysRemap);
        return List.copyOf(command);
    }

    private static List<String> base(
            String directory, String dumpFile, String sourceSchema, String jobName) {
        return new ArrayList<>(List.of(
                "DIRECTORY=" + directory,
                "DUMPFILE=" + dumpFile,
                "SCHEMAS=" + sourceSchema,
                "JOB_NAME=" + jobName,
                "NOLOGFILE=YES"));
    }

    private static void addSchemaRemap(
            List<String> command, String sourceSchema, String destinationSchema, boolean alwaysRemap) {
        if (alwaysRemap || !sourceSchema.equals(destinationSchema)) {
            command.add("REMAP_SCHEMA=" + sourceSchema + ":" + destinationSchema);
        }
    }
}
