package com.example.gb.service;

import com.example.gb.model.DomInventoryLatest;
import com.example.gb.model.dto.DomInventorySnapshot;
import com.example.gb.repository.DomInventoryLatestRepo;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DomInventoryLatestService {

    private final DomInventoryLatestRepo repo;

    @Transactional
    public SaveResult saveIfChanged(DomInventorySnapshot snap) {

        // 1) якщо є — оновлюємо тільки якщо hash інший
        var existingOpt = repo.findByPageKey(snap.pageKey());
        if (existingOpt.isPresent()) {
            return updateIfChanged(existingOpt.get(), snap);
        }

        // 2) нема — пробуємо вставити
        try {
            var e = new DomInventoryLatest();
            e.setPageKey(snap.pageKey());
            e.setPageUrl(snap.pageUrl());
            e.setOrigin(snap.origin());
            e.setItemsJson(snap.itemsJson());
            e.setItemsHash(snap.itemsHash());

            repo.saveAndFlush(e);
            return SaveResult.INSERTED;

        } catch (DataIntegrityViolationException ex) {
            // 3) якщо це НЕ конфлікт по uk_dom_inventory_page_key — прокидуємо далі
            if (!isDuplicatePageKey(ex)) {
                throw ex;
            }

            // 4) хтось вставив паралельно — перечитуємо і застосовуємо правило
            var e = repo.findByPageKey(snap.pageKey())
                    .orElseThrow(() -> ex);

            return updateIfChanged(e, snap);
        }
    }

    private SaveResult updateIfChanged(DomInventoryLatest e, DomInventorySnapshot snap) {
        // ВАЖЛИВО: при NO_CHANGE не робимо ЖОДНОГО set(...)
        if (Objects.equals(e.getItemsHash(), snap.itemsHash())) {
            return SaveResult.NO_CHANGE;
        }

        e.setPageUrl(snap.pageUrl());
        e.setOrigin(snap.origin());
        e.setItemsJson(snap.itemsJson());
        e.setItemsHash(snap.itemsHash());
        return SaveResult.UPDATED;
    }

    private boolean isDuplicatePageKey(DataIntegrityViolationException ex) {
        Throwable t = ex;
        while (t != null) {
            var msg = t.getMessage();
            if (msg != null && msg.contains("uk_dom_inventory_page_key")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
    public enum SaveResult { INSERTED, UPDATED, NO_CHANGE }
}
