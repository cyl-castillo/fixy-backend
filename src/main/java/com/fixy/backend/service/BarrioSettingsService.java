package com.fixy.backend.service;

import com.fixy.backend.model.BarrioSetting;
import com.fixy.backend.repository.BarrioSettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Interruptor en tiempo real de la auto-publicación. La fila gana; sin fila, manda la property. Sin cache. */
@Service
public class BarrioSettingsService {

  public static final String KEY_AUTO_PUBLISH = "ai_auto_publish";

  private final BarrioSettingRepository settings;
  private final boolean defaultAutoPublish;
  private final double minConfidence;
  private final int dailyCap;

  public BarrioSettingsService(
      BarrioSettingRepository settings,
      @Value("${fixy.barrio.ai-review.auto-approve:false}") boolean defaultAutoPublish,
      @Value("${fixy.barrio.ai-review.auto-approve-min-confidence:0.90}") double minConfidence,
      @Value("${fixy.barrio.ai-review.auto-approve-daily-cap:10}") int dailyCap
  ) {
    this.settings = settings;
    this.defaultAutoPublish = defaultAutoPublish;
    this.minConfidence = minConfidence;
    this.dailyCap = dailyCap;
  }

  public boolean autoPublish() {
    return settings.findById(KEY_AUTO_PUBLISH)
        .map(s -> Boolean.parseBoolean(s.getValue().trim()))
        .orElse(defaultAutoPublish);
  }

  public void setAutoPublish(boolean on) {
    BarrioSetting s = settings.findById(KEY_AUTO_PUBLISH).orElse(null);
    if (s == null) {
      settings.save(new BarrioSetting(KEY_AUTO_PUBLISH, Boolean.toString(on)));
    } else {
      s.setValue(Boolean.toString(on));
      settings.save(s);
    }
  }

  public double minConfidence() {
    return minConfidence;
  }

  public int dailyCap() {
    return dailyCap;
  }
}
