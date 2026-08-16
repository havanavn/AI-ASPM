package aspm.app.ui;

/**
 * Inline SVG icons. No icon font and no sprite request.
 *
 * <p>An icon font maps glyphs to private-use code points, which a screen reader may announce as
 * gibberish and which a missing font renders as a box. Inline SVG has neither failure mode, and ADR-058's
 * no-registry constraint rules out fetching a sprite anyway.
 *
 * <p>Every icon is {@code aria-hidden}. DOC-08 forbids colour as the sole carrier of meaning and the same
 * reasoning applies to shape: an icon is decoration beside a label, never the label. An icon that had to
 * be announced would mean the label was missing.
 */
public final class Icon {

    private Icon() {
    }

    private static String svg(String path) {
        return "<svg class=\"nav-icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" "
                + "stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\" "
                + "aria-hidden=\"true\" focusable=\"false\">" + path + "</svg>";
    }

    public static String dashboard() {
        return svg("<rect x='1.75' y='1.75' width='5.5' height='5.5' rx='1'/>"
                + "<rect x='8.75' y='1.75' width='5.5' height='3' rx='1'/>"
                + "<rect x='8.75' y='6.25' width='5.5' height='8' rx='1'/>"
                + "<rect x='1.75' y='8.75' width='5.5' height='5.5' rx='1'/>");
    }

    public static String finding() {
        return svg("<path d='M8 1.75 1.75 5v3.5c0 3.2 2.6 5.4 6.25 5.75 3.65-.35 6.25-2.55 6.25-5.75V5z'/>"
                + "<path d='M8 6v2.5'/><circle cx='8' cy='10.75' r='.6' fill='currentColor'/>");
    }

    public static String inventory() {
        return svg("<path d='M2 5.5 8 2.25l6 3.25v5L8 13.75 2 10.5z'/><path d='M2 5.5 8 8.75l6-3.25'/>"
                + "<path d='M8 8.75v5'/>");
    }

    public static String assessment() {
        return svg("<rect x='2.75' y='1.75' width='10.5' height='12.5' rx='1.5'/>"
                + "<path d='M5.5 6h5M5.5 8.75h5M5.5 11.5h3'/>");
    }

    public static String request() {
        return svg("<path d='M3.5 2.75h9a1 1 0 0 1 1 1v8.5a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1v-8.5a1 1 0 0 1 1-1z'/>"
                + "<path d='M6 6.5h4M6 9.25h4'/><path d='M11 1.5v2.5'/><path d='M5 1.5v2.5'/>");
    }

    public static String composition() {
        return svg("<circle cx='4' cy='4' r='2.25'/><circle cx='12' cy='4' r='2.25'/>"
                + "<circle cx='8' cy='12' r='2.25'/><path d='M5.6 5.7 7 9.9M10.4 5.7 9 9.9M6.2 4h3.6'/>");
    }

    public static String organization() {
        return svg("<rect x='6' y='1.75' width='4' height='3.5' rx='.75'/>"
                + "<rect x='1.75' y='10.75' width='4' height='3.5' rx='.75'/>"
                + "<rect x='10.25' y='10.75' width='4' height='3.5' rx='.75'/>"
                + "<path d='M8 5.25v3M3.75 10.75V8.25h8.5v2.5'/>");
    }

    public static String administration() {
        return svg("<circle cx='8' cy='8' r='2.25'/>"
                + "<path d='M8 1.75v1.6M8 12.65v1.6M1.75 8h1.6M12.65 8h1.6"
                + "M3.6 3.6l1.15 1.15M11.25 11.25l1.15 1.15M12.4 3.6l-1.15 1.15M4.75 11.25L3.6 12.4'/>");
    }

    /** An open book. The guide is a document, and the metaphor for a document is not a question mark. */
    public static String guide() {
        return svg("<path d='M8 4.25C6.9 3.3 5.6 2.9 3.5 2.9c-.7 0-1.25.1-1.75.25v9.1c.5-.15 1.05-.25 "
                + "1.75-.25 2.1 0 3.4.4 4.5 1.35'/>"
                + "<path d='M8 4.25c1.1-.95 2.4-1.35 4.5-1.35.7 0 1.25.1 1.75.25v9.1c-.5-.15-1.05-.25-"
                + "1.75-.25-2.1 0-3.4.4-4.5 1.35z'/><path d='M8 4.25v9.1'/>");
    }

    public static String search() {
        return svg("<circle cx='7' cy='7' r='4.25'/><path d='M10.2 10.2 14 14'/>");
    }

    public static String chevron() {
        return svg("<path d='M6 4l4 4-4 4'/>");
    }

    /** DOC-08 §9's unmeasured state. A dashed outline, because the value is absent rather than zero. */
    public static String unmeasured() {
        return "<svg class=\"state-icon\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" "
                + "stroke-width=\"1.5\" stroke-dasharray=\"3 3\" aria-hidden=\"true\" focusable=\"false\">"
                + "<circle cx='12' cy='12' r='9'/></svg>";
    }

    public static String empty() {
        return "<svg class=\"state-icon\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" "
                + "stroke-width=\"1.5\" aria-hidden=\"true\" focusable=\"false\">"
                + "<rect x='3.5' y='5.5' width='17' height='13' rx='2'/><path d='M3.5 10h17'/></svg>";
    }
}
