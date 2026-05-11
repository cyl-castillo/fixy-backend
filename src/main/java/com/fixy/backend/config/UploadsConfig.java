package com.fixy.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sirve los uploads como recursos estáticos en {urlPrefix}/**. nginx
 * cacheará respuestas con Content-Type correcto. Para que el cliente
 * abra una foto basta con la URL devuelta por LeadPhotoResponse.
 */
@Configuration
public class UploadsConfig implements WebMvcConfigurer {

  private final String urlPrefix;
  private final String uploadsDir;

  public UploadsConfig(
      @Value("${fixy.uploads.dir:./data/uploads}") String uploadsDir,
      @Value("${fixy.uploads.url-prefix:/uploads}") String urlPrefix
  ) {
    this.uploadsDir = uploadsDir;
    this.urlPrefix = urlPrefix.replaceAll("/+$", "");
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String location = "file:" + uploadsDir + (uploadsDir.endsWith("/") ? "" : "/");
    registry.addResourceHandler(urlPrefix + "/**")
        .addResourceLocations(location)
        .setCachePeriod(60 * 60 * 24 * 30); // 30 días
  }
}
