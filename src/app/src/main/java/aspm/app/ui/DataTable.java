package aspm.app.ui;

import aspm.app.resource.ResourceGroup;
import aspm.module.insight.domain.PresentationState;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The data table. DOC-08 §4 density, §8 components, §9 states.
 *
 * <p>This is the surface an AppSec engineer spends the day in, and the first version of it was a bare
 * grid whose headers were schema column names. Two things were wrong with that beyond appearance: a
 * reader cannot tell {@code scope_node_id} from {@code owning_node_id} at a glance, and a column order
 * taken from the schema puts {@code row_version} beside the title.
 *
 * <p>So the table has a <b>presentation order</b> and <b>rendered cell types</b>. Both are derived from
 * the descriptor rather than hand-written per group, so a new resource group gets them for free — which
 * was the point of the descriptor.
 *
 * <h2>What is kept from the plain version</h2>
 *
 * <ul>
 *   <li><b>A null cell is a state, never a blank.</b> {@code PRD-UIX-022}: an empty cell is read as zero
 *       or as "none", and for a security platform both are claims the data does not support.
 *   <li><b>Severity is a pill with a text label and a distinct shape</b>, so the rank survives monochrome
 *       print and colour-blindness. Colour is reinforcement.
 *   <li><b>Rows are focusable and the header is sticky</b>, because {@code INT-UIX-003} requires keyboard
 *       completion and a table you must scroll to identify is a table you misread.
 * </ul>
 */
public final class DataTable {

    private DataTable() {
    }

    /** Columns that carry identity or status and belong at the start, in this order. */
    private static final List<String> LEAD = List.of(
            "code", "title", "display_name", "name", "state", "lifecycle_state", "finding_class");

    /** Columns that are machinery rather than content. Last, and muted. */
    private static final Set<String> TRAILING = Set.of(
            "row_version", "created_at", "updated_at", "first_seen_at", "last_detected_at",
            "first_detected_at", "last_confirmed_at");

    public static String render(Messages messages, ResourceGroup group,
            List<Map<String, Object>> items, boolean filterActive, Map<String, String> query) {
        if (items.isEmpty()) {
            return StateRenderer.state(messages,
                    filterActive ? PresentationState.EMPTY_FILTERED : PresentationState.EMPTY_NO_DATA,
                    Optional.of(messages.get(filterActive ? "table.filtered" : emptyKey(group))));
        }

        List<String> columns = order(group);
        StringBuilder out = new StringBuilder(2048);

        out.append(toolbar(messages, group, query));
        out.append("<div class=\"table-wrap\"><div class=\"table-scroll\"><table class=\"data\">");
        out.append("<caption>")
                .append(Html.text(messages.get("table.rowCount", Integer.valueOf(items.size()))))
                .append("</caption><thead><tr>");
        for (String column : columns) {
            out.append("<th scope=\"col\"").append(numeric(group, column) ? " class=\"num\"" : "")
                    .append(">").append(Html.text(header(column))).append("</th>");
        }
        out.append("</tr></thead><tbody>");
        for (Map<String, Object> item : items) {
            out.append("<tr tabindex=\"-1\">");
            boolean first = true;
            for (String column : columns) {
                out.append(cell(messages, group, column, item.get(column), first));
                first = false;
            }
            out.append("</tr>");
        }
        out.append("</tbody></table></div>");
        out.append("<div class=\"card-footer row between\"><span>")
                .append(Html.text(messages.get("table.rowCount", Integer.valueOf(items.size()))))
                .append("</span><span class=\"subtle\">")
                .append(Html.text(messages.get("table.keysetOnly"))).append("</span></div>");
        out.append("</div>");
        return out.toString();
    }

    /** A short table for a dashboard card. Lead columns only. */
    public static String compact(Messages messages, ResourceGroup group,
            List<Map<String, Object>> items) {
        if (items.isEmpty()) {
            return StateRenderer.state(messages, PresentationState.EMPTY_NO_DATA,
                    Optional.of(messages.get(emptyKey(group))));
        }
        List<String> columns = order(group).stream().limit(3).toList();
        StringBuilder out = new StringBuilder();
        out.append("<div class=\"table-scroll\"><table class=\"data\"><thead><tr>");
        for (String column : columns) {
            out.append("<th scope=\"col\">").append(Html.text(header(column))).append("</th>");
        }
        out.append("</tr></thead><tbody>");
        for (Map<String, Object> item : items.stream().limit(6).toList()) {
            out.append("<tr tabindex=\"-1\">");
            boolean first = true;
            for (String column : columns) {
                out.append(cell(messages, group, column, item.get(column), first));
                first = false;
            }
            out.append("</tr>");
        }
        out.append("</tbody></table></div>");
        return out.toString();
    }

    /**
     * The filter toolbar.
     *
     * <p>Only the declared filterable fields appear. {@code PRD-API-020} rejects a filter on an
     * undeclared field rather than ignoring it, and offering a control for one would invite the request
     * the API refuses — an interface that can express something the API rejects is an interface that
     * teaches its users to be wrong.
     */
    private static String toolbar(Messages messages, ResourceGroup group, Map<String, String> query) {
        if (group.filterable().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("<form class=\"toolbar no-print\" method=\"get\">");
        for (String field : group.filterable().stream().sorted().toList()) {
            out.append("<div class=\"field\"><label for=\"f-").append(Html.text(field)).append("\">")
                    .append(Html.text(header(field))).append("</label>")
                    .append("<input id=\"f-").append(Html.text(field)).append("\" name=")
                    .append(Html.attribute(field)).append(" value=")
                    .append(Html.attribute(query.getOrDefault(field, "")))
                    .append(" autocomplete=\"off\"></div>");
        }
        out.append("<button class=\"btn btn-sm btn-primary\" type=\"submit\">")
                .append(Html.text(messages.get("action.filter"))).append("</button>")
                .append("<a class=\"btn btn-sm btn-ghost\" href=\"?\">")
                .append(Html.text(messages.get("action.clear"))).append("</a>")
                .append("</form>");
        return out.toString();
    }

    private static List<String> order(ResourceGroup group) {
        List<String> all = List.copyOf(group.exposed().keySet());
        List<String> lead = LEAD.stream().filter(all::contains).toList();
        List<String> middle = all.stream()
                .filter(c -> !lead.contains(c) && !TRAILING.contains(c) && !"id".equals(c))
                .toList();
        List<String> trailing = all.stream().filter(TRAILING::contains).toList();
        return java.util.stream.Stream.of(lead, middle, trailing)
                .flatMap(List::stream).toList();
    }

    /**
     * A human header from a column name.
     *
     * <p>⚠ Derived, not translated, and that is a stated limitation rather than an oversight.
     * {@code INT-UIX-013} requires tenant vocabulary overrides to apply everywhere a term appears, and
     * the override is data the interface has no query for yet. A hand-written English label would be
     * neither translatable nor overridable, so the column name is de-slugged and the gap stays visible.
     */
    private static String header(String column) {
        String spaced = column.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static boolean numeric(ResourceGroup group, String column) {
        return group.exposed().get(column) == ResourceGroup.ColumnKind.INTEGER;
    }

    private static String emptyKey(ResourceGroup group) {
        return switch (group.name()) {
            case "findings" -> "table.empty.findings";
            case "assets" -> "table.empty.assets";
            default -> "table.empty.generic";
        };
    }

    private static String cell(Messages messages, ResourceGroup group, String column, Object value,
            boolean primary) {
        ResourceGroup.ColumnKind kind = group.exposed().get(column);
        StringBuilder out = new StringBuilder("<td");
        if (kind == ResourceGroup.ColumnKind.INTEGER) {
            out.append(" class=\"num tabular\"");
        } else if (primary) {
            out.append(" class=\"cell-primary truncate\"");
        } else if (TRAILING.contains(column)) {
            out.append(" class=\"cell-secondary\"");
        }
        out.append('>');

        if (value == null) {
            // Never blank. A blank cell is read as zero or as "none", and both are claims the data does
            // not support (PRD-UIX-022).
            out.append("<span class=\"state state-inline\"><span class=\"state-label\">")
                    .append(Html.text(messages.get("state.unmeasured"))).append("</span></span>");
            return out.append("</td>").toString();
        }

        String text = String.valueOf(value);
        if (isSeverityOrState(column)) {
            out.append(pill(text));
        } else if (kind == ResourceGroup.ColumnKind.UUID) {
            // Identifiers are read character by character and transcribed (PRD-UIX-005). Monospace, and
            // shortened with the full value available to a screen reader and on hover.
            out.append("<span class=\"id-chip\" title=").append(Html.attribute(text)).append(">")
                    .append(Html.text(text.length() > 8 ? text.substring(0, 8) : text))
                    .append("</span><span class=\"visually-hidden\">").append(Html.text(text))
                    .append("</span>");
        } else if (kind == ResourceGroup.ColumnKind.TIMESTAMP) {
            // INT-UIX-010: the zone is indicated where ambiguity is possible, and it always is here.
            out.append("<time datetime=").append(Html.attribute(text)).append(">")
                    .append(Html.text(text.length() >= 16 ? text.substring(0, 16).replace('T', ' ')
                            : text)).append(" UTC</time>");
        } else if (kind == ResourceGroup.ColumnKind.TEXT_ARRAY && "[]".equals(text)) {
            out.append("<span class=\"subtle\">—</span>");
        } else {
            out.append(Html.text(text));
        }
        return out.append("</td>").toString();
    }

    private static boolean isSeverityOrState(String column) {
        return "state".equals(column) || "lifecycle_state".equals(column)
                || "finding_class".equals(column) || column.endsWith("severity");
    }

    /**
     * A status pill.
     *
     * <p>Colour is reinforcement and the label is the signal. The shape differs per severity as well, so
     * the ranking survives a monochrome print and a reader who cannot distinguish the hues — which
     * DOC-08 requires and which a security tool cannot treat as optional, since misreading a severity is
     * the expensive mistake.
     */
    private static String pill(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        String variant = switch (normalized) {
            case "critical", "revoked", "expired" -> "critical";
            case "high", "open", "reopened" -> "high";
            case "medium", "in_progress", "requested" -> "medium";
            case "low", "closed", "resolved", "active", "approved" -> "low";
            case "secret" -> "critical";
            default -> "info";
        };
        return "<span class=\"pill pill-" + variant + "\">" + Html.text(value) + "</span>";
    }
}
