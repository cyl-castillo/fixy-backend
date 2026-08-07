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

  /**
   * Patrón LOCAL del handler a partir del url-prefix configurado. En prod el
   * prefix es una URL ABSOLUTA ("https://api.fixy.com.uy/uploads", para que
   * las URLs devueltas al cliente sean completas) — usarla entera como
   * patrón hacía que el handler nunca matcheara y NINGÚN upload se sirviera
   * (bug real de prod destapado por las notas de voz 2026-08-07: las fotos
   * daban 404 igual). El handler debe registrarse solo con el path.
   */
  public static String handlerPathFor(String urlPrefix) {
    String prefix = urlPrefix.replaceAll("/+$", "");
    int scheme = prefix.indexOf("://");
    if (scheme >= 0) {
      int path = prefix.indexOf('/', scheme + 3);
      prefix = path >= 0 ? prefix.substring(path) : "/uploads";
    }
    return prefix.isEmpty() ? "/uploads" : prefix;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String location = "file:" + uploadsDir + (uploadsDir.endsWith("/") ? "" : "/");
    registry.addResourceHandler(handlerPathFor(urlPrefix) + "/**")
        .addResourceLocations(location)
        .setCachePeriod(60 * 60 * 24 * 30); // 30 días
  }
}
