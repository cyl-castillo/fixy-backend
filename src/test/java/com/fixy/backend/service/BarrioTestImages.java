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

}
