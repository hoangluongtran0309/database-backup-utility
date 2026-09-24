package com.hoangluongtran0309.dbbackup.web.controller;

import java.util.EnumSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.hoangluongtran0309.dbbackup.application.notification.ManageNotificationChannelService;
import com.hoangluongtran0309.dbbackup.application.notification.ManageTargetNotificationService;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.core.model.NotificationEventType;
import com.hoangluongtran0309.dbbackup.core.model.TargetNotificationSubscription;
import com.hoangluongtran0309.dbbackup.web.dto.TargetNotificationForm;

import lombok.RequiredArgsConstructor;

@Controller
@RequestMapping("/databases/{targetId}/notifications")
@RequiredArgsConstructor
public class TargetNotificationController {
    private static final NotificationEventType[] EVENTS = java.util.Arrays.stream(NotificationEventType.values())
            .filter(NotificationEventType::isSubscribable).toArray(NotificationEventType[]::new);
    private final ManageTargetNotificationService targetNotifications;
    private final ManageNotificationChannelService channels;
    private final ManageDatabaseTargetService targets;

    @GetMapping String edit(@PathVariable UUID targetId, Model model, RedirectAttributes flash) {
        try {
            TargetNotificationForm form = buildForm(targetId);
            model.addAttribute("form", form);
            return view(targetId, model);
        } catch (NoSuchElementException e) {
            flash.addFlashAttribute("error", "That target no longer exists");
            return "redirect:/databases";
        }
    }

    @PostMapping String save(@PathVariable UUID targetId,
            @ModelAttribute("form") TargetNotificationForm form, BindingResult binding,
            Model model, RedirectAttributes flash) {
        for (int index = 0; index < form.getRows().size(); index++) {
            TargetNotificationForm.Row row = form.getRows().get(index);
            if (row.isIncluded() && (row.getEvents() == null || row.getEvents().isEmpty())) {
                binding.rejectValue("rows[" + index + "].events", "notification.events",
                        "Choose at least one event for every selected channel");
            }
        }
        if (!binding.hasErrors()) {
            try {
                targetNotifications.replace(targetId, form.toSubscriptions());
                flash.addFlashAttribute("message", "Notification subscriptions saved");
                return "redirect:/databases";
            } catch (IllegalArgumentException e) {
                binding.reject("notification.invalid", e.getMessage());
            } catch (NoSuchElementException e) {
                flash.addFlashAttribute("error", "That target no longer exists");
                return "redirect:/databases";
            }
        }
        return view(targetId, model);
    }

    private TargetNotificationForm buildForm(UUID targetId) {
        Map<UUID, TargetNotificationSubscription> current = targetNotifications.subscriptionsFor(targetId).stream()
                .collect(Collectors.toMap(TargetNotificationSubscription::channelId, Function.identity()));
        TargetNotificationForm form = new TargetNotificationForm();
        form.setRows(channels.listAll().stream().map(channel -> {
            TargetNotificationForm.Row row = new TargetNotificationForm.Row();
            row.setChannelId(channel.getId());
            TargetNotificationSubscription existing = current.get(channel.getId());
            if (existing != null) {
                row.setIncluded(true);
                row.setEvents(EnumSet.copyOf(existing.events()));
            }
            return row;
        }).toList());
        return form;
    }

    private String view(UUID targetId, Model model) {
        model.addAttribute("target", targets.get(targetId));
        model.addAttribute("channels", channels.listAll());
        model.addAttribute("events", EVENTS);
        return "database/notifications";
    }
}
