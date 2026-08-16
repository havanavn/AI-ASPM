package aspm.app.ui;

import aspm.app.runtime.Dispatcher;
import java.util.List;
import java.util.Map;

/**
 * Form fragments shared by the interface. ADR-058 — server-rendered, no build step.
 *
 * <h2>Why the idempotency field is here and not written inline</h2>
 *
 * <p>Class B and class E operations require replay protection (ADR-036), and the dispatcher enforces it.
 * An HTML form cannot set a request header, so a form posting to one of those routes carries its key as a
 * hidden field. That was missing everywhere: the transition buttons on the request detail page posted to a
 * class B operation with no key and received 400 for as long as the page existed.
 *
 * <p>Written once, here, because the failure mode of duplicating it is a form that silently lacks the
 * field — and the symptom is a 400 on one page and not the others, which reads as a routing problem rather
 * than a missing input.
 */
public final class Forms {

    private Forms() {
    }

    /**
     * A hidden idempotency key for one rendered form.
     *
     * <p>A fresh random value per render, deliberately. What replay protection needs is a value the client
     * fixes and repeats on retry: a double submit or a refresh-repost of this rendered page sends the same
     * key. Minting a new one per submission attempt — which a script setting a header would do — protects
     * against nothing.
     *
     * <p>⚠ The dispatcher currently validates the key's shape and tenant-namespaces it; there is no
     * request-response store, so a replayed key is accepted rather than recognised and short-circuited.
     * That gap is in {@code deploy/README.md} rather than implied away here: the key being present is what
     * makes adding the store a change in one place.
     */
    public static String idempotencyField() {
        return "<input type=\"hidden\" name=\"" + Dispatcher.IDEMPOTENCY_FIELD + "\" value="
                + Html.attribute(java.util.UUID.randomUUID().toString()) + ">";
    }

    /**
     * Opens a fieldset that is disabled unless the caller has stepped up.
     *
     * <p>A {@code <fieldset disabled>} disables every control inside it natively, and <b>a disabled
     * control is not submitted</b>. That is the property being used: a caller who has not elevated cannot
     * post a partial form by any route, including one they hand-craft, and the dispatcher's step-up gate
     * remains the control regardless.
     *
     * <p>Why the pages do this at all rather than letting the gate refuse the submission: it refuses AFTER
     * the caller has typed. Elevation lasts five minutes and the form is often a permission matrix with
     * thirty boxes; losing it once to a challenge nobody warned about is how people learn to distrust a
     * form. So the page asks first and shows the form read-only until it is answered.
     */
    public static String fieldsetOpen(boolean elevated) {
        return "<fieldset class=\"plain\"" + (elevated ? "" : " disabled") + ">";
    }

    public static String fieldsetClose() {
        return "</fieldset>";
    }

    /**
     * The prompt shown in place of a submit button when the caller has not stepped up.
     *
     * <p>{@code returnTo} is a path this code constructs, never a caller-supplied value — an unchecked
     * return path on an authentication surface is an open redirect, and {@code AccountPages.safeNext}
     * refuses anything outside {@code /} on the receiving side as well.
     */
    public static String elevationPrompt(Messages messages, String returnTo) {
        return "<div class=\"banner\" role=\"note\"><div><strong>"
                + Html.text(messages.get("admin.stepUp.title")) + "</strong> "
                + Html.text(messages.get("admin.stepUp.body"))
                + " <a class=\"btn btn-primary btn-sm ms-2\" href="
                + Html.attribute("/step-up?next=" + java.net.URLEncoder.encode(returnTo,
                        java.nio.charset.StandardCharsets.UTF_8))
                + ">" + Html.text(messages.get("admin.stepUp.action")) + "</a></div></div>";
    }

    /** A labelled text input inside the application shell, matching the card grammar of DOC-08. */
    public static String field(Messages messages, String name, String labelKey, String type,
            String value, boolean required, String hint) {
        return field(messages, name, labelKey, type, value, required, hint, null);
    }

    /**
     * The same, with the label supplied directly.
     *
     * <p>Needed because a TENANT-DECLARED attribute has no message key: its label lives in
     * {@code asset_attribute_definition.label_i18n}, which is data. A field whose label had to be a
     * message key could only ever render fields somebody wrote code for.
     */
    public static String field(Messages messages, String name, String labelKey, String type,
            String value, boolean required, String hint, String literalLabel) {
        String label = literalLabel != null ? literalLabel : messages.get(labelKey);
        return "<label class=\"col gap-1\">"
                + "<span class=\"fs-12 muted\">" + Html.text(label) + "</span>"
                + "<input class=\"input\" name=" + Html.attribute(name)
                + " type=" + Html.attribute(type)
                + " value=" + Html.attribute(value)
                + (required ? " required" : "")
                + " autocomplete=\"off\">"
                + (hint == null ? "" : "<span class=\"fs-11 muted\">" + Html.text(hint) + "</span>")
                + "</label>";
    }

    /** A labelled select. Options are (value, label) pairs, already translated. */
    public static String select(Messages messages, String name, String labelKey,
            List<Map.Entry<String, String>> options, String selected) {
        return select(messages, name, labelKey, options, selected, null);
    }

    /** The same, with a literal label — for a tenant-declared attribute, whose label is data. */
    public static String select(Messages messages, String name, String labelKey,
            List<Map.Entry<String, String>> options, String selected, String literalLabel) {
        String label = literalLabel != null ? literalLabel : messages.get(labelKey);
        StringBuilder out = new StringBuilder();
        out.append("<label class=\"col gap-1\">")
                .append("<span class=\"fs-12 muted\">").append(Html.text(label))
                .append("</span><select class=\"input\" name=").append(Html.attribute(name)).append(">");
        for (Map.Entry<String, String> option : options) {
            out.append("<option value=").append(Html.attribute(option.getKey()))
                    .append(option.getKey().equals(selected) ? " selected" : "").append(">")
                    .append(Html.text(option.getValue())).append("</option>");
        }
        out.append("</select></label>");
        return out.toString();
    }

    /**
     * A type-and-filter person picker: a text input bound to a {@code <datalist>}.
     *
     * <p><b>Why not a select.</b> A dropdown is fine for five options and unusable for fifty. There
     * is no keyboard search in a native select beyond first-letter matching, so naming somebody in a
     * list of several dozen means scrolling and reading — every time, for the two fields that get set
     * on every request. {@code PRD-UIX-013}'s keyboard-first obligation is not met by a control whose
     * only efficient operation is a mouse drag.
     *
     * <p><b>Why a datalist and not a scripted combobox.</b> The filtering, the popup and the keyboard
     * handling are the browser's, so the control works with script disabled and does not need an
     * accessible-name and active-descendant implementation that a hand-rolled listbox would have to
     * get right. The cost is that the form submits the TEXT and not an identifier, so the server has
     * to resolve it — see {@code RequestPages#resolvePerson}, where an unresolvable name is an error
     * shown to the person rather than a silently ignored field (PP-9).
     *
     * @param options identifier to label; the label is what the person types against
     * @param selectedId the currently named person, or null
     */
    public static String personPicker(Messages messages, String name, String labelKey,
            List<Map.Entry<String, String>> options, String selectedId, String hint) {
        String listId = "people-" + name;
        String current = options.stream()
                .filter(o -> o.getKey().equals(selectedId == null ? "" : selectedId))
                .map(Map.Entry::getValue).findFirst().orElse("");
        StringBuilder out = new StringBuilder();
        out.append("<label class=\"col gap-1\">")
                .append("<span class=\"fs-12 muted\">")
                .append(Html.text(messages.get(labelKey))).append("</span>")
                .append("<input class=\"input\" type=\"text\" autocomplete=\"off\" ")
                .append("name=").append(Html.attribute(name))
                .append(" list=").append(Html.attribute(listId))
                .append(" value=").append(Html.attribute(current))
                .append(" placeholder=").append(Html.attribute(messages.get("form.person.placeholder")))
                .append(">")
                .append("<datalist id=").append(Html.attribute(listId)).append(">");
        for (Map.Entry<String, String> option : options) {
            out.append("<option value=").append(Html.attribute(option.getValue())).append("></option>");
        }
        out.append("</datalist>");
        if (hint != null) {
            out.append("<span class=\"fs-11 muted\">").append(Html.text(hint)).append("</span>");
        }
        return out.append("</label>").toString();
    }

    /** A checkbox with a literal label, for a tenant-declared BOOLEAN attribute. */
    public static String checkboxLabelled(Messages messages, String name, String label,
            boolean checked) {
        return "<label class=\"row gap-2 items-center\">"
                + "<input type=\"hidden\" name=" + Html.attribute(name) + " value=\"false\">"
                + "<input type=\"checkbox\" name=" + Html.attribute(name) + " value=\"true\""
                + (checked ? " checked" : "") + ">"
                + "<span class=\"fs-13\">" + Html.text(label) + "</span></label>";
    }

    /** A checkbox whose unchecked state is submitted, so a cleared setting is a value and not an absence. */
    public static String checkbox(Messages messages, String name, String labelKey, boolean checked) {
        return "<label class=\"row gap-2 items-center\">"
                // The paired hidden field is what makes "off" arrive. An unchecked box submits NOTHING,
                // so a settings form without this cannot distinguish "the user cleared it" from "the
                // field was not on the form" — and would silently keep the old value.
                + "<input type=\"hidden\" name=" + Html.attribute(name) + " value=\"false\">"
                + "<input type=\"checkbox\" name=" + Html.attribute(name) + " value=\"true\""
                + (checked ? " checked" : "") + ">"
                + "<span class=\"fs-13\">" + Html.text(messages.get(labelKey)) + "</span>"
                + "</label>";
    }
}
