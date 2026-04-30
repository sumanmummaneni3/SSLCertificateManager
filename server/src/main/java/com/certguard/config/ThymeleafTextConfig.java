package com.certguard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Registers an additional Thymeleaf resolver for plain-text (.txt) email templates.
 * The resolver uses TEXT mode and has higher priority than the default HTML resolver
 * so that *.txt templates are processed in the correct mode.
 */
@Configuration
public class ThymeleafTextConfig {

    @Bean
    public ClassLoaderTemplateResolver textEmailTemplateResolver() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setOrder(1);
        resolver.setPrefix("templates/");
        resolver.setSuffix("");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCheckExistence(true);
        return resolver;
    }
}
