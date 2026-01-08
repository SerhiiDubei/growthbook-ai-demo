package com.example.gb.model;

import com.example.gb.model.base.AbstractVersional;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;


@Data
@Entity
@Table(
        name = "dom_inventory_latest",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_dom_inventory_page_key", columnNames = "page_key")
        }
)
public class DomInventoryLatest extends AbstractVersional {

    @Column(name = "page_key", nullable = false, length = 255)
    private String pageKey;

    @Column(nullable = false, length = 255)
    private String origin;

    @Column(name = "page_url", columnDefinition = "text", nullable = false)
    private String pageUrl;

    @Column(name = "items_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String itemsJson;

    @Column(name = "items_hash", nullable = false, length = 64)
    private String itemsHash;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;
}
