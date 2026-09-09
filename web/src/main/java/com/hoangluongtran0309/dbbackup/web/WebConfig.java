package com.hoangluongtran0309.dbbackup.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // There is one thing to look at so far, so the root is not a dashboard.
        registry.addRedirectViewController("/", "/databases");
    }
}
