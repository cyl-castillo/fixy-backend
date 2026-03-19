package com.fixy.backend.service;

import com.fixy.backend.dto.ProviderCatalogItem;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProviderCatalogService {

  public List<ProviderCatalogItem> list() {
    return List.of(
        new ProviderCatalogItem("Electricista Demo", "electricidad", "Pocitos", "099000111"),
        new ProviderCatalogItem("Plomero Norte", "plomeria", "Centro", "099000222"),
        new ProviderCatalogItem("Cerrajería Rápida", "cerrajeria", "Cordón", "099000333"),
        new ProviderCatalogItem("Barométrica Sur", "barometrica", "Montevideo", "099000444"),
        new ProviderCatalogItem("Reparaciones Hogar Uy", "reparaciones", "Carrasco", "099000555")
    );
  }
}
