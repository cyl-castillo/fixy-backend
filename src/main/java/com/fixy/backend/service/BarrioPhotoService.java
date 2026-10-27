package com.fixy.backend.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fotos de Fixy Barrio (B1): valida como {@link ImageUploadValidator}, respeta la orientación
 * EXIF, QUITA todo el EXIF (GPS, modelo del celular…: es un mapa público, la foto no puede delatar
 * a quien la sacó), reduce a máx. 1600 px del lado mayor y guarda siempre JPEG bajo
 * {@code uploads/barrio/}. Re-codificar con ImageIO descarta toda metadata por construcción: no hay
 * lista de tags que mantener.
 */
@Service
public class BarrioPhotoService {

  static final long MAX_BYTES = 8L * 1024 * 1024;
  static final int MAX_SIDE = 1600;
  private static final long MAX_PIXELS = 60_000_000L;
  private static final String DIR = "barrio";

  private final Path uploadsRoot;
  private final String urlPrefix;
  private final SecureRandom random = new SecureRandom();

  public BarrioPhotoService(
      @Value("${fixy.uploads.dir:./data/uploads}") String uploadsDir,
      @Value("${fixy.uploads.url-prefix:/uploads}") String urlPrefix
  ) {
    this.uploadsRoot = Path.of(uploadsDir).toAbsolutePath().normalize();
    this.urlPrefix = urlPrefix.replaceAll("/+$", "");
    try {
      Files.createDirectories(this.uploadsRoot.resolve(DIR));
    } catch (IOException e) {
      throw new IllegalStateException("cannot create uploads dir: " + this.uploadsRoot, e);
    }
  }

  /** Foto subida por la web: 413 si pasa de 8 MB, 400 si no es jpg/png/webp decodificable. */
  public String store(MultipartFile file) {
    if (file != null && file.getSize() > MAX_BYTES) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "foto demasiado grande (max 8 MB)");
    }
    ImageUploadValidator.validate(file, MAX_BYTES);
    try {
      return store(file.getBytes());
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no se pudo leer la foto");
    }
  }

  /** Foto cruda (descarga de WhatsApp o ya leída): procesa y guarda; devuelve el path relativo. */
  public String store(byte[] raw) {
    if (raw == null || raw.length == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "archivo vacio");
    }
    if (raw.length > MAX_BYTES) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "foto demasiado grande (max 8 MB)");
    }
    byte[] jpeg = process(raw);
    String relative = DIR + "/" + randomHex(12) + ".jpg";
    Path target = uploadsRoot.resolve(relative).normalize();
    if (!target.startsWith(uploadsRoot)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid filename");
    }
    try {
      Files.createDirectories(target.getParent());
      Files.write(target, jpeg);
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "no se pudo guardar la foto");
    }
    return relative;
  }

  public void deleteQuietly(String relative) {
    if (relative == null || relative.isBlank()) return;
    Path target = uploadsRoot.resolve(relative).normalize();
    if (!target.startsWith(uploadsRoot.resolve(DIR))) return;
    try {
      Files.deleteIfExists(target);
    } catch (IOException ignored) {
      // best-effort: un archivo huérfano no rompe nada
    }
  }

  public String urlFor(String relative) {
    if (relative == null || relative.isBlank()) return null;
    return urlPrefix + "/" + relative;
  }

  /** Decodifica, aplica orientación EXIF, reduce y re-codifica a JPEG sin metadata. */
  static byte[] process(byte[] raw) {
    BufferedImage decoded = decode(raw);
    int orientation = ExifOrientation.read(raw);
    BufferedImage oriented = applyOrientation(decoded, orientation);
    BufferedImage scaled = scaleDown(oriented, MAX_SIDE);
    return encodeJpeg(scaled);
  }

  private static BufferedImage decode(byte[] raw) {
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "la imagen no es valida");
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in, true, true);
        long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
        if (pixels <= 0 || pixels > MAX_PIXELS) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "la imagen no es valida");
        }
        BufferedImage image = reader.read(0);
        if (image == null) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "la imagen no es valida");
        }
        return image;
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException e) {
      if (e instanceof ResponseStatusException rse) throw rse;
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "la imagen no es valida");
    }
  }

  static BufferedImage applyOrientation(BufferedImage src, int orientation) {
    int w = src.getWidth();
    int h = src.getHeight();
    AffineTransform tx;
    boolean swap = false;
    switch (orientation) {
      case 2 -> tx = new AffineTransform(-1, 0, 0, 1, w, 0);
      case 3 -> tx = new AffineTransform(-1, 0, 0, -1, w, h);
      case 4 -> tx = new AffineTransform(1, 0, 0, -1, 0, h);
      case 5 -> { tx = new AffineTransform(0, 1, 1, 0, 0, 0); swap = true; }
      case 6 -> { tx = new AffineTransform(0, 1, -1, 0, h, 0); swap = true; }
      case 7 -> { tx = new AffineTransform(0, -1, -1, 0, h, w); swap = true; }
      case 8 -> { tx = new AffineTransform(0, -1, 1, 0, 0, w); swap = true; }
      default -> { return src; }
    }
    BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    try {
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, out.getWidth(), out.getHeight());
      g.drawImage(src, tx, null);
    } finally {
      g.dispose();
    }
    return out;
  }

  private static BufferedImage scaleDown(BufferedImage src, int maxSide) {
    int w = src.getWidth();
    int h = src.getHeight();
    int longest = Math.max(w, h);
    int tw = w;
    int th = h;
    if (longest > maxSide) {
      double ratio = (double) maxSide / longest;
      tw = Math.max(1, (int) Math.round(w * ratio));
      th = Math.max(1, (int) Math.round(h * ratio));
    }
    BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    try {
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, tw, th);
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.drawImage(src, 0, 0, tw, th, null);
    } finally {
      g.dispose();
    }
    return out;
  }

  private static byte[] encodeJpeg(BufferedImage image) {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "no se pudo procesar la foto");
    }
    ImageWriter writer = writers.next();
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
         MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos)) {
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(0.85f);
      writer.setOutput(out);
      // IIOImage sin metadata: no se escribe ni EXIF ni XMP ni comentarios.
      writer.write(null, new IIOImage(image, null, null), param);
      out.flush();
      return bos.toByteArray();
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "no se pudo procesar la foto");
    } finally {
      writer.dispose();
    }
  }

  private String randomHex(int bytes) {
    byte[] buf = new byte[bytes];
    random.nextBytes(buf);
    return HexFormat.of().formatHex(buf);
  }

  /** Lector mínimo del tag Orientation (0x0112) del EXIF de un JPEG. 1 si no hay o no es legible. */
  static final class ExifOrientation {
    private ExifOrientation() {
    }

    static int read(byte[] d) {
      try {
        if (d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) return 1;
        int pos = 2;
        while (pos + 4 <= d.length) {
          if ((d[pos] & 0xFF) != 0xFF) return 1;
          int marker = d[pos + 1] & 0xFF;
          if (marker == 0xDA || marker == 0xD9) return 1;
          int len = ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
          if (marker == 0xE1 && len >= 14 && pos + 4 + 6 <= d.length
              && d[pos + 4] == 'E' && d[pos + 5] == 'x' && d[pos + 6] == 'i' && d[pos + 7] == 'f') {
            return readTiff(d, pos + 10, Math.min(d.length, pos + 2 + len));
          }
          pos += 2 + len;
        }
      } catch (RuntimeException ignored) {
        // EXIF corrupto: se asume orientación normal
      }
      return 1;
    }

    private static int readTiff(byte[] d, int tiff, int end) {
      if (tiff + 8 > end) return 1;
      boolean le = d[tiff] == 'I' && d[tiff + 1] == 'I';
      if (!le && !(d[tiff] == 'M' && d[tiff + 1] == 'M')) return 1;
      int ifd = tiff + (int) u32(d, tiff + 4, le);
      if (ifd < tiff || ifd + 2 > end) return 1;
      int count = u16(d, ifd, le);
      for (int i = 0; i < count; i++) {
        int entry = ifd + 2 + i * 12;
        if (entry + 12 > end) return 1;
        if (u16(d, entry, le) == 0x0112) {
          int value = u16(d, entry + 8, le);
          return value >= 1 && value <= 8 ? value : 1;
        }
      }
      return 1;
    }

    private static int u16(byte[] d, int p, boolean le) {
      int a = d[p] & 0xFF;
      int b = d[p + 1] & 0xFF;
      return le ? (b << 8) | a : (a << 8) | b;
    }

    private static long u32(byte[] d, int p, boolean le) {
      long v = 0;
      for (int i = 0; i < 4; i++) {
        int b = d[le ? p + 3 - i : p + i] & 0xFF;
        v = (v << 8) | b;
      }
      return v;
    }
  }
}
