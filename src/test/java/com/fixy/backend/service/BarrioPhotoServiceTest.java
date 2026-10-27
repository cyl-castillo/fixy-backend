package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class BarrioPhotoServiceTest {

  private static boolean contains(byte[] hay, String needle) {
    return new String(hay, StandardCharsets.ISO_8859_1).contains(needle);
  }

  @Test
  void quitaElExifYGuardaJpegEnUploadsBarrio(@TempDir Path tmp) throws Exception {
    BarrioPhotoService svc = new BarrioPhotoService(tmp.toString(), "/uploads");
    byte[] input = BarrioTestImages.withExif(BarrioTestImages.jpeg(200, 100), 1);
    assertThat(contains(input, "Exif")).isTrue();
    assertThat(contains(input, "GPS-SECRETO")).isTrue();

    String relative = svc.store(input);

    assertThat(relative).startsWith("barrio/").endsWith(".jpg");
    byte[] saved = Files.readAllBytes(tmp.resolve(relative));
    assertThat(contains(saved, "Exif")).isFalse();
    assertThat(contains(saved, "GPS-SECRETO")).isFalse();
    assertThat(ImageIO.read(new ByteArrayInputStream(saved))).isNotNull();
    assertThat(svc.urlFor(relative)).isEqualTo("/uploads/" + relative);
  }

  @Test
  void reduceAMil600PxElLadoMayor() throws Exception {
    byte[] out = BarrioPhotoService.process(BarrioTestImages.jpeg(3200, 2400));
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(out));
    assertThat(img.getWidth()).isEqualTo(1600);
    assertThat(img.getHeight()).isEqualTo(1200);
  }

  @Test
  void noAgrandaLasFotosChicas() throws Exception {
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(BarrioPhotoService.process(BarrioTestImages.jpeg(300, 200))));
    assertThat(img.getWidth()).isEqualTo(300);
    assertThat(img.getHeight()).isEqualTo(200);
  }

  @Test
  void respetaLaOrientacionExifAntesDeQuitarla() throws Exception {
    // 200x100 con orientación 6 (rotar 90° horario): queda 100x200, y lo rojo (izquierda) pasa ARRIBA.
    byte[] out = BarrioPhotoService.process(BarrioTestImages.withExif(BarrioTestImages.jpeg(200, 100), 6));
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(out));
    assertThat(img.getWidth()).isEqualTo(100);
    assertThat(img.getHeight()).isEqualTo(200);
    Color top = new Color(img.getRGB(50, 20));
    Color bottom = new Color(img.getRGB(50, 180));
    assertThat(top.getRed()).isGreaterThan(150);
    assertThat(bottom.getBlue()).isGreaterThan(150);
  }

  @Test
  void rechazaLoQueNoEsUnaImagen(@TempDir Path tmp) {
    BarrioPhotoService svc = new BarrioPhotoService(tmp.toString(), "/uploads");
    assertThatThrownBy(() -> svc.store("esto no es una imagen".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    MockMultipartFile pdf = new MockMultipartFile("photo", "x.pdf", "application/pdf", new byte[] {1, 2, 3});
    assertThatThrownBy(() -> svc.store(pdf)).isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void fotoMasGrandeQueElLimiteDa413(@TempDir Path tmp) {
    BarrioPhotoService svc = new BarrioPhotoService(tmp.toString(), "/uploads");
    MockMultipartFile big = new MockMultipartFile("photo", "x.jpg", "image/jpeg", new byte[9 * 1024 * 1024]);
    assertThatThrownBy(() -> svc.store(big))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(413));
  }

  @Test
  void deleteQuietlySoloBorraDentroDeBarrio(@TempDir Path tmp) throws Exception {
    BarrioPhotoService svc = new BarrioPhotoService(tmp.toString(), "/uploads");
    String relative = svc.store(BarrioTestImages.jpeg(50, 50));
    Path outside = tmp.resolve("lead-1.jpg");
    Files.writeString(outside, "ajeno");
    svc.deleteQuietly("../lead-1.jpg");
    svc.deleteQuietly("lead-1.jpg");
    assertThat(outside).exists();
    svc.deleteQuietly(relative);
    assertThat(tmp.resolve(relative)).doesNotExist();
  }
}
