package com.hoangluongtran0309.dbbackup.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hoangluongtran0309.dbbackup.application.notification.ManageNotificationChannelService;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannel;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;
import com.hoangluongtran0309.dbbackup.web.security.SecurityConfig;

@WebMvcTest(NotificationChannelController.class)
@Import(SecurityConfig.class)
class NotificationChannelControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ManageNotificationChannelService service;

    @Test void notificationsRequireAuthentication() throws Exception {
        mvc.perform(get("/notifications")).andExpect(status().is3xxRedirection());
    }

    @Test @WithMockUser void mutationRequiresCsrf() throws Exception {
        mvc.perform(post("/notifications").with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
    }

    @Test @WithMockUser void createsAWebhookChannel() throws Exception {
        when(service.create(any())).thenReturn(channel(UUID.randomUUID()));
        mvc.perform(post("/notifications").with(csrf())
                        .param("name", "automation").param("type", "WEBHOOK")
                        .param("webhookUrl", "https://example.com/hook?token=secret"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/notifications"));
        verify(service).create(any());
    }

    @Test @WithMockUser void validationFailureNeverEchoesSubmittedWebhook() throws Exception {
        mvc.perform(post("/notifications").with(csrf())
                        .param("name", "").param("type", "WEBHOOK")
                        .param("webhookUrl", "https://example.com/must-not-return"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("must-not-return"))));
    }

    @Test @WithMockUser void editNeverRendersStoredCiphertext() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(channel(id));
        mvc.perform(get("/notifications/{id}/edit", id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Leave empty to keep")))
                .andExpect(content().string(not(containsString("sealed-webhook-never-in-html"))));
    }

    @Test @WithMockUser void listShowsChannelWithoutItsSecret() throws Exception {
        when(service.listAll()).thenReturn(List.of(channel(UUID.randomUUID())));
        mvc.perform(get("/notifications")).andExpect(status().isOk())
                .andExpect(content().string(containsString("automation")))
                .andExpect(content().string(not(containsString("sealed-webhook-never-in-html"))));
    }

    private static NotificationChannel channel(UUID id) {
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        return NotificationChannel.builder().id(id).name("automation").type(NotificationChannelType.WEBHOOK)
                .webhookUrlCiphertext("sealed-webhook-never-in-html").createdAt(now).updatedAt(now).build();
    }
}
