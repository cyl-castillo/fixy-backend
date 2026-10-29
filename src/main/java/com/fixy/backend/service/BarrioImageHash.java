package com.fixy.backend.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Huella perceptual dHash de 64 bits (Java puro): la imagen se reduce a 9x8 en gris promediando
 * cada celda (determinista, sin depender del interpolador de AWT) y cada bit dice si un píxel es
 * más claro que su vecino de la derecha. Dos fotos casi iguales (re-encode, leve reescalado) quedan
 * a pocos bits de distancia; fotos distintas, a ~32.
 */
public final class BarrioImageHash {

  private static final int COLS = 9;
  private static final int ROWS = 8;

  private BarrioImageHash() {
  }

  /** dHash de una imagen decodificable; lanza IllegalArgumentException si no lo es. */
  public static long dhash(byte[] image) {
    BufferedImage img;
    try {
      img = ImageIO.read(new ByteArrayInputStream(image));
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("imagen ilegible", e);
    }
    if (img == null || img.getWidth() < 1 || img.getHeight() < 1) {
      throw new IllegalArgumentException("imagen ilegible");
    }
    int w = img.getWidth();
    int h = img.getHeight();
    double[][] cell = new double[ROWS][COLS];
    for (int cy = 0; cy < ROWS; cy++) {
      int y0 = cy * h / ROWS;
      int y1 = Math.max(y0 + 1, (cy + 1) * h / ROWS);
      for (int cx = 0; cx < COLS; cx++) {
        int x0 = cx * w / COLS;
        int x1 = Math.max(x0 + 1, (cx + 1) * w / COLS);
        double sum = 0;
        long n = 0;
        for (int y = y0; y < Math.min(y1, h); y++) {
          for (int x = x0; x < Math.min(x1, w); x++) {
            int rgb = img.getRGB(x, y);
            sum += 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
            n++;
          }
        }
        cell[cy][cx] = n == 0 ? 0 : sum / n;
      }
    }
    long hash = 0;
    for (int y = 0; y < ROWS; y++) {
      for (int x = 0; x < COLS - 1; x++) {
        hash = (hash << 1) | (cell[y][x] > cell[y][x + 1] ? 1 : 0);
      }
    }
    return hash;
  }

  public static int hamming(long a, long b) {
    return Long.bitCount(a ^ b);
  }
}
