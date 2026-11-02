package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.project.SourceChannelDefinitionComparison;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.domain.inventory.CanIdMatchDiagnostic;
import com.stellar.platform.domain.inventory.CanIdMatchDiagnosticStatus;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;

import java.text.NumberFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Read-only DBC candidate comparison dialog. Presentation only — uses cached comparisons.
 */
public final class DbcCompareDialog {

    private DbcCompareDialog() {
    }

    public static void show(SourceChannelKey key, List<SourceChannelDefinitionComparison> comparisons) {
        Objects.requireNonNull(key, "key");
        List<SourceChannelDefinitionComparison> rows = comparisons == null ? List.of() : comparisons;
        NumberFormat numberFormat = NumberFormat.getIntegerInstance(Locale.US);
        NumberFormat percentFormat = NumberFormat.getNumberInstance(Locale.US);
        percentFormat.setMaximumFractionDigits(2);
        percentFormat.setMinimumFractionDigits(0);

        StringBuilder body = new StringBuilder();
        body.append("Channel ").append(key.channelId())
                .append('\n')
                .append("Candidates: ").append(rows.size()).append('\n');
        for (SourceChannelDefinitionComparison cmp : rows.stream()
                .sorted(Comparator.comparing(SourceChannelDefinitionComparison::definitionDisplayName))
                .toList()) {
            body.append('\n').append("DBC: ").append(cmp.definitionDisplayName()).append('\n');
            body.append("  Coverage: ")
                    .append(numberFormat.format(cmp.matchedUniqueMessageCount()))
                    .append(" / ")
                    .append(numberFormat.format(cmp.observedUniqueMessageCount()))
                    .append(" · ")
                    .append(percentFormat.format(cmp.coveragePercent()))
                    .append("%\n");
            body.append("  Matched CAN IDs:\n");
            boolean anyMatched = false;
            for (CanIdMatchDiagnostic d : cmp.diagnostics()) {
                if (d.status() != CanIdMatchDiagnosticStatus.MATCHED) {
                    continue;
                }
                anyMatched = true;
                body.append("    0x")
                        .append(Long.toHexString(d.observedCanId()).toUpperCase(Locale.ROOT))
                        .append(' ')
                        .append(d.observedTypeLabel())
                        .append('\n');
            }
            if (!anyMatched) {
                body.append("    (none)\n");
            }
            body.append("  Unmatched observed CAN IDs:\n");
            boolean anyUnmapped = false;
            for (CanIdMatchDiagnostic d : cmp.diagnostics()) {
                if (d.status() == CanIdMatchDiagnosticStatus.MATCHED) {
                    continue;
                }
                anyUnmapped = true;
                body.append("    0x")
                        .append(Long.toHexString(d.observedCanId()).toUpperCase(Locale.ROOT))
                        .append(' ')
                        .append(d.observedTypeLabel())
                        .append(" -> ")
                        .append(d.status())
                        .append('\n');
            }
            if (!anyUnmapped) {
                body.append("    (none)\n");
            }
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("CAN ID Comparison");
        dialog.setHeaderText("Ch" + key.channelId()
                + " — read-only cached comparison (" + rows.size() + " DBC candidates)");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        TextArea area = new TextArea(body.toString());
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefRowCount(24);
        area.setPrefColumnCount(80);
        ScrollPane scroll = new ScrollPane(area);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(700, 460);
        dialog.getDialogPane().setContent(scroll);
        dialog.showAndWait();
    }
}
