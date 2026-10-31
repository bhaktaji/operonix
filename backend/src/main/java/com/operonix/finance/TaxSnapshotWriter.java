package com.operonix.finance;

import com.operonix.common.money.MoneyCalculator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the printed tax rows of a document line (LLD §17.7).
 *
 * <p>Rows are snapshots: they are written once when a document is priced and are never recomputed,
 * so a later rate change or profile edit cannot alter an issued document's tax breakdown.
 */
@Component
public class TaxSnapshotWriter {

    public static final String QUOTE = "QUOTE";
    public static final String INVOICE = "INVOICE";

    private final LineTaxComponentRepository components;

    public TaxSnapshotWriter(LineTaxComponentRepository components) {
        this.components = components;
    }

    /** Replaces the rows of the given lines. Runs in the caller's transaction with the document. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(UUID tenantId, String lineType, Map<UUID, List<MoneyCalculator.TaxComponentAmount>> byLine) {
        if (byLine.isEmpty()) {
            return;
        }
        components.deleteAllByTenantIdAndLineTypeAndLineIdIn(tenantId, lineType, byLine.keySet());
        List<LineTaxComponent> rows = new ArrayList<>();
        byLine.forEach((lineId, amounts) -> {
            int position = 0;
            for (MoneyCalculator.TaxComponentAmount amount : amounts) {
                LineTaxComponent row = new LineTaxComponent();
                row.setTenantId(tenantId);
                row.setLineType(lineType);
                row.setLineId(lineId);
                row.setCode(amount.code());
                row.setName(amount.name());
                row.setRate(amount.rate());
                row.setBase(amount.base());
                row.setAmount(amount.amount());
                row.setPosition(position++);
                rows.add(row);
            }
        });
        components.saveAll(rows);
    }

    /** Component rows for the given lines, grouped by line id and ordered by position. */
    @Transactional(readOnly = true)
    public Map<UUID, List<FinanceDtos.TaxAmountResponse>> read(UUID tenantId, String lineType,
                                                              Collection<UUID> lineIds) {
        if (lineIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<FinanceDtos.TaxAmountResponse>> byLine = new LinkedHashMap<>();
        for (LineTaxComponent row : components
                .findAllByTenantIdAndLineTypeAndLineIdInOrderByPositionAsc(tenantId, lineType, lineIds)) {
            byLine.computeIfAbsent(row.getLineId(), key -> new ArrayList<>())
                    .add(new FinanceDtos.TaxAmountResponse(row.getCode(), row.getName(), row.getRate(),
                            row.getBase(), row.getAmount()));
        }
        return byLine;
    }
}
