package com.fixy.backend.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** JPEGs sintéticos para los tests de Fixy Barrio (con y sin EXIF). */
public final class BarrioTestImages {

  private BarrioTestImages() {
  }

  /** JPEG de w x h: mitad izquierda roja, mitad derecha azul (para verificar la rotación). */
  public static byte[] jpeg(int w, int h) throws IOException {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(Color.RED);
    g.fillRect(0, 0, w / 2, h);
    g.setColor(Color.BLUE);
    g.fillRect(w / 2, 0, w - w / 2, h);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "jpeg", out);
    return out.toByteArray();
  }

  /** Inserta un APP1/EXIF (little endian) con Orientation + un bloque con "GPS-SECRETO" tras el SOI. */
  public static byte[] withExif(byte[] jpeg, int orientation) {
    byte[] secret = "GPS-SECRETO-Pixel7".getBytes(StandardCharsets.US_ASCII);
    ByteArrayOutputStream tiff = new ByteArrayOutputStream();
    tiff.writeBytes(new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0});            // header, IFD0 en offset 8
    tiff.writeBytes(new byte[] {1, 0});                                     // 1 entrada
    tiff.writeBytes(new byte[] {0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0}); // Orientation
    tiff.writeBytes(new byte[] {0, 0, 0, 0});                               // next IFD
    tiff.writeBytes(secret);
    byte[] t = tiff.toByteArray();
    int len = 2 + 6 + t.length;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, (byte) (len >> 8), (byte) len});
    out.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(t);
    out.write(jpeg, 2, jpeg.length - 2);
    return out.toByteArray();
  }

  /** JPEG con bloques de gris pseudoaleatorios (semilla): fotos distintas = huellas distintas. */
  public static byte[] textured(long seed, int w, int h, float quality) throws IOException {
    java.util.Random rnd = new java.util.Random(seed);
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    int cells = 12;
    for (int y = 0; y < cells; y++) {
      for (int x = 0; x < cells; x++) {
        g.setColor(new Color(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)));
        g.fillRect(x * w / cells, y * h / cells, w / cells + 1, h / cells + 1);
      }
    }
    g.dispose();
    var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    var param = writer.getDefaultWriteParam();
    param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
    param.setCompressionQuality(quality);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (var ios = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(ios);
      writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
    }
    writer.dispose();
    return out.toByteArray();
  }

  public static byte[] textured(long seed) throws IOException {
    return textured(seed, 640, 480, 0.9f);
  }

  /**
   * Inserta un APP1/EXIF (little endian) con DateTime (IFD0) y/o DateTimeOriginal (sub-IFD Exif).
   * Fechas en formato EXIF "yyyy:MM:dd HH:mm:ss"; null = no se escribe ese tag.
   */
  public static byte[] withExifDates(byte[] jpeg, String dateTime, String dateTimeOriginal) {
    ByteArrayOutputStream tiff = new ByteArrayOutputStream();
    tiff.writeBytes(new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0});
    int ifd0Entries = (dateTime != null ? 1 : 0) + (dateTimeOriginal != null ? 1 : 0);
    int ifd0Size = 2 + ifd0Entries * 12 + 4;
    int exifIfdOffset = 8 + ifd0Size;
    int exifIfdSize = dateTimeOriginal != null ? 2 + 12 + 4 : 0;
    int dtOffset = exifIfdOffset + exifIfdSize;
    int dtoOffset = dtOffset + (dateTime != null ? 20 : 0);
    tiff.writeBytes(le16(ifd0Entries));
    if (dateTime != null) tiff.writeBytes(entry(0x0132, 2, 20, dtOffset));
    if (dateTimeOriginal != null) tiff.writeBytes(entry(0x8769, 4, 1, exifIfdOffset));
    tiff.writeBytes(new byte[] {0, 0, 0, 0});
    if (dateTimeOriginal != null) {
      tiff.writeBytes(le16(1));
      tiff.writeBytes(entry(0x9003, 2, 20, dtoOffset));
      tiff.writeBytes(new byte[] {0, 0, 0, 0});
    }
    if (dateTime != null) tiff.writeBytes(ascii20(dateTime));
    if (dateTimeOriginal != null) tiff.writeBytes(ascii20(dateTimeOriginal));
    return app1(jpeg, tiff.toByteArray());
  }

  /** APP1/EXIF con basura: cabecera TIFF válida pero offsets fuera de rango. */
  public static byte[] withCorruptExif(byte[] jpeg) {
    byte[] t = {'I', 'I', 42, 0, 8, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0x32, 0x01, 2, 0, (byte) 0xFF, (byte) 0xFF};
    return app1(jpeg, t);
  }

  private static byte[] app1(byte[] jpeg, byte[] tiff) {
    int len = 2 + 6 + tiff.length;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, (byte) (len >> 8), (byte) len});
    out.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(tiff);
    out.write(jpeg, 2, jpeg.length - 2);
    return out.toByteArray();
  }

  private static byte[] le16(int v) {
    return new byte[] {(byte) v, (byte) (v >> 8)};
  }

  private static byte[] entry(int tag, int type, int count, int value) {
    return new byte[] {(byte) tag, (byte) (tag >> 8), (byte) type, (byte) (type >> 8),
        (byte) count, (byte) (count >> 8), 0, 0, (byte) value, (byte) (value >> 8), (byte) (value >> 16), 0};
  }

  private static byte[] ascii20(String s) {
    byte[] out = new byte[20];
    byte[] b = s.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(b, 0, out, 0, Math.min(19, b.length));
    return out;
  }

}
