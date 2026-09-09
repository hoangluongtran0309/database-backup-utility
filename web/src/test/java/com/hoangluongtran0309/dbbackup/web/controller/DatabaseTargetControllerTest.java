package com.hoangluongtran0309.dbbackup.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.application.target.TestTargetConnectionService;
import com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;

@WebMvcTest(DatabaseTargetController.class)
class DatabaseTargetControllerTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-09-09T11:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ManageDatabaseTargetService service;

    @MockitoBean
    private TestTargetConnectionService connectionTest;

    @Test
    void listsTargets() throws Exception {
        when(service.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/databases"))
                .andExpect(status().isOk())
                .andExpect(view().name("database/list"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("production")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("127.0.0.1:3306/shop")));
    }

    @Test
    void showsAnEmptyStateWhenThereAreNoTargets() throws Exception {
        when(service.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/databases"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No targets yet")));
    }

    @Test
    void newFormDefaultsToTheMysqlPort() throws Exception {
        mockMvc.perform(get("/databases/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("database/form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"3306\"")));
    }

    @Test
    void registersAValidTargetAndRedirects() throws Exception {
        when(service.register(any())).thenReturn(target("production"));

        mockMvc.perform(post("/databases")
                        .param("name", "production")
                        .param("host", "127.0.0.1")
                        .param("port", "3306")
                        .param("database", "shop")
                        .param("username", "backup")
                        .param("password", "s3cr3t"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"));

        verify(service).register(any());
    }

    @Test
    void redisplaysTheFormWithTheTypedValuesWhenAFieldIsInvalid() throws Exception {
        mockMvc.perform(post("/databases")
                        .param("name", "")
                        .param("host", "db.internal")
                        .param("port", "3306")
                        .param("database", "shop")
                        .param("username", "backup")
                        .param("password", "s3cr3t"))
                .andExpect(status().isOk())
                .andExpect(view().name("database/form"))
                .andExpect(model().attributeHasFieldErrors("form", "name"))
                // What the operator already typed must survive the round trip.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"db.internal\"")));

        verify(service, never()).register(any());
    }

    @Test
    void reportsADuplicateNameOnTheNameField() throws Exception {
        when(service.register(any())).thenThrow(new DuplicateTargetNameException("production"));

        mockMvc.perform(post("/databases")
                        .param("name", "production")
                        .param("host", "127.0.0.1")
                        .param("port", "3306")
                        .param("database", "shop")
                        .param("username", "backup")
                        .param("password", "s3cr3t"))
                .andExpect(status().isOk())
                .andExpect(view().name("database/form"))
                .andExpect(model().attributeHasFieldErrors("form", "name"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    void neverRendersTheSubmittedPasswordBackIntoThePage() throws Exception {
        mockMvc.perform(post("/databases")
                        .param("name", "")
                        .param("host", "127.0.0.1")
                        .param("port", "3306")
                        .param("database", "shop")
                        .param("username", "backup")
                        .param("password", "s3cr3t"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("s3cr3t"))));
    }

    @Test
    void showsNeverTestedForATargetThatHasNotBeenProbed() throws Exception {
        when(service.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/databases"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Never tested")));
    }

    @Test
    void showsTheStoredFailureMessageOnTheListPage() throws Exception {
        when(service.listAll()).thenReturn(List.of(tested(ConnectionCheck.failed(
                "ERROR 1045 (28000): Access denied for user 'backup'", CHECKED_AT))));

        mockMvc.perform(get("/databases"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Failed")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Access denied for user")));
    }

    @Test
    void showsConnectedForATargetThatPassed() throws Exception {
        when(service.listAll()).thenReturn(List.of(tested(ConnectionCheck.passed(CHECKED_AT))));

        mockMvc.perform(get("/databases"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Connected")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-09-09 11:00")));
    }

    @Test
    void testingATargetReportsSuccessAndRedirects() throws Exception {
        UUID id = UUID.randomUUID();
        when(connectionTest.test(id)).thenReturn(ConnectionCheck.passed(CHECKED_AT));

        mockMvc.perform(post("/databases/{id}/test", id))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"))
                .andExpect(flash().attribute("message", "Connection succeeded"));
    }

    @Test
    void testingATargetSurfacesMysqlsMessageOnFailure() throws Exception {
        UUID id = UUID.randomUUID();
        when(connectionTest.test(id)).thenReturn(
                ConnectionCheck.failed("ERROR 1045 (28000): Access denied", CHECKED_AT));

        mockMvc.perform(post("/databases/{id}/test", id))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error",
                        "Connection failed: ERROR 1045 (28000): Access denied"));
    }

    @Test
    void testingATargetDeletedInAnotherTabDoesNotBlowUp() throws Exception {
        UUID id = UUID.randomUUID();
        when(connectionTest.test(id)).thenThrow(new NoSuchElementException("gone"));

        mockMvc.perform(post("/databases/{id}/test", id))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"))
                .andExpect(flash().attribute("error", "That target no longer exists"));
    }

    @Test
    void deletesATarget() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/databases/{id}/delete", id))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"));

        verify(service).delete(id);
    }

    private static DatabaseTarget target(String name) {
        return target(name, null);
    }

    private static DatabaseTarget tested(ConnectionCheck check) {
        return target("production", check);
    }

    private static DatabaseTarget target(String name, ConnectionCheck check) {
        return DatabaseTarget.builder()
                .id(UUID.randomUUID())
                .name(name)
                .host("127.0.0.1")
                .port(3306)
                .databaseName("shop")
                .username("backup")
                .passwordCiphertext("Y2lwaGVydGV4dA==")
                .createdAt(Instant.parse("2026-09-09T10:15:30Z"))
                .lastConnectionCheck(check)
                .build();
    }
}
