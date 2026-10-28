package com.fixy.backend.repository;

import com.fixy.backend.model.BarrioSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BarrioSettingRepository extends JpaRepository<BarrioSetting, String> {
}
