package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** Lector EXIF de fecha y dHash de los cierres de Avisá (C1). */
class BarrioClosureMetaTest {

  @Test
  void dateTimeOriginalSeInterpretaEnMontevideo() throws Exception {
    byte[] jpeg = BarrioTestImages.withExifDates(BarrioTestImages.jpeg(64, 48), "2020:01:01 00:00:00", "2026:10:20 14:30:15");
    OffsetDateTime t = BarrioPhotoService.ExifDate.read(jpeg);
    assertThat(t).isNotNull();
    assertThat(t.toLocalDateTime()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 20, 14, 30, 15));
    assertThat(t.getOffset().getTotalSeconds()).isEqualTo(-3 * 3600);
  }

  @Test
  void dateTimeComunSiNoHayOriginal() throws Exception {
    byte[] jpeg = BarrioTestImages.withExifDates(BarrioTestImages.jpeg(64, 48), "2026:09:05 08:00:00", null);
    assertThat(BarrioPhotoService.ExifDate.read(jpeg).toLocalDateTime())
        .isEqualTo(java.time.LocalDateTime.of(2026, 9, 5, 8, 0, 0));
  }

  @Test
  void sinExifOExifCorruptoDaNull() throws Exception {
    byte[] plain = BarrioTestImages.jpeg(64, 48);
    assertThat(BarrioPhotoService.ExifDate.read(plain)).isNull();
    assertThat(BarrioPhotoService.ExifDate.read(BarrioTestImages.withExif(plain, 6))).isNull(); // solo Orientation
    assertThat(BarrioPhotoService.ExifDate.read(BarrioTestImages.withCorruptExif(plain))).isNull();
    assertThat(BarrioPhotoService.ExifDate.read(new byte[] {1, 2, 3})).isNull();
    byte[] garbageDate = BarrioTestImages.withExifDates(plain, "no es una fecha!!!", null);
    assertThat(BarrioPhotoService.ExifDate.read(garbageDate)).isNull();
  }

  @Test
  void laOrientacionSigueFuncionando() throws Exception {
    assertThat(BarrioPhotoService.ExifOrientation.read(BarrioTestImages.withExif(BarrioTestImages.jpeg(64, 48), 6))).isEqualTo(6);
  }

  @Test
  void storeWithMetaLeeLaFechaDelOriginalYElResultadoQuedaSinExif(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
    BarrioPhotoService svc = new BarrioPhotoService(tmp.toString(), "/uploads");
    byte[] raw = BarrioTestImages.withExifDates(BarrioTestImages.textured(1), null, "2026:10:20 14:30:15");
    BarrioPhotoService.StoredPhoto stored = svc.storeWithMeta(raw);
    assertThat(stored.takenAt()).isNotNull();
    assertThat(BarrioPhotoService.ExifDate.read(svc.read(stored.path()))).isNull(); // re-codificado: sin metadata
    assertThat(stored.dhash()).isEqualTo(BarrioImageHash.dhash(svc.read(stored.path())));
    assertThat(svc.storeWithMeta(BarrioTestImages.textured(1)).takenAt()).isNull();
  }

  @Test
  void dhashMismaImagenCeroDistintaMasDe8ReencodeLeveHasta8() throws Exception {
    byte[] a = BarrioTestImages.textured(1, 640, 480, 0.9f);
    byte[] b = BarrioTestImages.textured(2, 640, 480, 0.9f);
    assertThat(BarrioImageHash.hamming(BarrioImageHash.dhash(a), BarrioImageHash.dhash(a))).isZero();
    assertThat(BarrioImageHash.hamming(BarrioImageHash.dhash(a), BarrioImageHash.dhash(b))).isGreaterThan(8);
    byte[] reencoded = BarrioTestImages.textured(1, 640, 480, 0.6f);
    byte[] smaller = BarrioTestImages.textured(1, 480, 360, 0.8f);
    assertThat(BarrioImageHash.hamming(BarrioImageHash.dhash(a), BarrioImageHash.dhash(reencoded))).isLessThanOrEqualTo(8);
    assertThat(BarrioImageHash.hamming(BarrioImageHash.dhash(a), BarrioImageHash.dhash(smaller))).isLessThanOrEqualTo(8);
  }
}
