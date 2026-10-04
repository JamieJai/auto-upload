package com.autoreg.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Page 응답을 안정된 DTO 형태({content, page})로 내보내고, 이미지 파일을 /files/** 로 서빙한다 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class WebConfig implements WebMvcConfigurer {

    private final String imageRoot;

    public WebConfig(@Value("${autoreg.image-root}") String imageRoot) {
        this.imageRoot = imageRoot.endsWith("/") ? imageRoot : imageRoot + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/files/**").addResourceLocations("file:" + imageRoot);
    }
}
