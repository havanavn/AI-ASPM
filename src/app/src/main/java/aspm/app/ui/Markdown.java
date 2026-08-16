package aspm.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A deliberately small Markdown renderer for finding write-ups, proofs of concept and comments.
 *
 * <h2>Why this exists rather than a rich-text editor</h2>
 *
 * <p>The content this renders is <b>attacker-influenced by design</b>. A pentester writing up a
 * cross-site scripting finding pastes the payload that worked; a comment on an ingested finding
 * quotes text an attacker authored. DOC-26 names indirect prompt injection through ingested findings
 * as one of the five highest-risk surfaces precisely because "finding content legitimately includes
 * attacker-authored text".
 *
 * <p>A rich-text editor stores HTML and sanitises on the way out. That puts the control in every
 * consumer: the page, the export, the email, the API. One consumer that forgets is a stored
 * cross-site scripting vulnerability <b>in the product whose purpose is finding them in other
 * people's software</b>. Storing Markdown and rendering with a strict subset inverts the failure: a
 * consumer that forgets to render shows escaped text, which is safe and merely ugly.
 *
 * <h2>What it accepts</h2>
 *
 * <p>Headings, paragraphs, unordered and ordered lists, fenced and inline code, bold, italic, links,
 * and block quotes. That covers a finding write-up and a set of reproduction steps.
 *
 * <p>What it does <b>not</b> accept, each for a stated reason:
 *
 * <ul>
 *   <li><b>Raw HTML</b> — escaped, never passed through. There is no allowlist to get wrong.
 *   <li><b>Images by URL</b> — an image tag pointing at an attacker's host is an exfiltration beacon
 *       that fires when a reviewer opens the finding, reporting who read it and when. Attachments go
 *       through {@code evidence}, which records a verified media type and a malware verdict.
 *   <li><b>Link schemes other than http and https</b> — {@code javascript:} and {@code data:} are
 *       script execution wearing a link, and {@code data:} is also a way to smuggle an image past the
 *       rule above.
 * </ul>
 *
 * <p>Every link additionally carries {@code rel="noopener noreferrer nofollow"} and opens in place:
 * a finding write-up that opens a tab with a window handle back to this application hands the linked
 * page a reference it can navigate.
 */
public final class Markdown {

    /** Bounded so one comment cannot make a page unrenderable. */
    private static final int MAX_LENGTH = 64_000;

    private Markdown() {
    }

    /**
     * Renders to HTML.
     *
     * <p>Every text fragment passes through {@link Html#text}, so the escaping is not a step this
     * method could forget — there is no path that appends raw input.
     */
    public static String render(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        String text = source.length() > MAX_LENGTH ? source.substring(0, MAX_LENGTH) : source;
        List<String> lines = List.of(text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1));

        StringBuilder out = new StringBuilder(text.length() * 2);
        List<String> paragraph = new ArrayList<>();
        String listOpen = null;
        // The item being read, held until its continuation lines have arrived. See flushItem.
        StringBuilder item = new StringBuilder();
        // Heading ids already used in THIS document, so a repeated title cannot produce two of them.
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        boolean inCode = false;
        StringBuilder code = new StringBuilder();
        int skipUntil = -1;
        int index = -1;

        for (String raw : lines) {
            index++;
            if (index < skipUntil) {
                continue;
            }
            String line = raw.stripTrailing();

            if (line.stripLeading().startsWith("```")) {
                if (inCode) {
                    out.append("<pre class=\"md-code\"><code>").append(Html.text(code.toString()))
                            .append("</code></pre>");
                    code.setLength(0);
                    inCode = false;
                } else {
                    flushParagraph(out, paragraph);
                    flushItem(out, item);
                    listOpen = closeList(out, listOpen);
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                code.append(line).append('\n');
                continue;
            }

            if (line.isBlank()) {
                flushParagraph(out, paragraph);
                flushItem(out, item);
                listOpen = closeList(out, listOpen);
                continue;
            }

            int heading = headingLevel(line);
            if (heading > 0) {
                flushParagraph(out, paragraph);
                flushItem(out, item);
                listOpen = closeList(out, listOpen);
                // Headings start at h3: the page owns h1 and the card owns h2, and a comment that
                // could emit an h1 would reorder the document outline a screen reader navigates by.
                int level = Math.min(6, heading + 2);
                String title = line.substring(heading).strip();
                // *** THE ID IS EMITTED HERE, AND THAT IS THE WHOLE FIX. ***
                //
                // The guide's contents list used to add ids to the headings from the browser, after
                // render. It worked once and then vanished: the component re-rendered when it stored
                // the contents it had just built, React re-applied the same HTML, and every attribute
                // added by hand went with it. Measured — twenty-seven links, zero targets, and a
                // click that changed the address bar and moved nothing.
                //
                // An id that arrives WITH the markup cannot be lost to a re-render, and it makes every
                // heading in every rendered document addressable rather than only the guide's.
                String id = slug(title, ids);
                out.append("<h").append(level).append(" class=\"md-h\" id=\"").append(id).append("\">")
                        .append(inline(title))
                        .append("</h").append(level).append('>');
                continue;
            }

            String trimmed = line.stripLeading();
            if (trimmed.startsWith("> ")) {
                flushParagraph(out, paragraph);
                flushItem(out, item);
                listOpen = closeList(out, listOpen);
                out.append("<blockquote class=\"md-quote\">")
                        .append(inline(trimmed.substring(2))).append("</blockquote>");
                continue;
            }

            // A table: a header row, a separator of dashes, then body rows. Pipe-delimited, which is
            // what a person pastes out of a spreadsheet and what every Markdown editor produces.
            if (trimmed.startsWith("|") && paragraph.isEmpty()) {
                int consumed = table(out, lines, index);
                if (consumed > 0) {
                    flushItem(out, item);
                    listOpen = closeList(out, listOpen);
                    skipUntil = index + consumed;
                    continue;
                }
            }

            boolean bullet = trimmed.startsWith("- ") || trimmed.startsWith("* ");
            boolean ordered = trimmed.matches("^\\d+\\.\\s.*");
            if (bullet || ordered) {
                flushParagraph(out, paragraph);
                String wanted = bullet ? "ul" : "ol";
                if (!wanted.equals(listOpen)) {
                    flushItem(out, item);
                    listOpen = closeList(out, listOpen);
                    out.append('<').append(wanted).append(" class=\"md-list\">");
                    listOpen = wanted;
                }
                flushItem(out, item);
                item.append(bullet ? trimmed.substring(2)
                        : trimmed.substring(trimmed.indexOf('.') + 1).stripLeading());
                continue;
            }

            // *** A WRAPPED LIST ITEM BELONGS TO ITS ITEM. ***
            //
            // An item used to be exactly one line, so a continuation fell through to the paragraph
            // branch below: it closed the list, became a paragraph of its own, and — worse — split the
            // inline markup across two calls, so a `*phrase*` that wrapped rendered its asterisks
            // literally. Both were visible on the first long document this renderer was given, which is
            // the user guide, and neither was visible on the short comments it was built for.
            //
            // The continuation must be INDENTED. Requiring that is what keeps this from swallowing the
            // paragraph that follows a list, which is a real and common shape.
            if (listOpen != null && item.length() > 0 && !trimmed.isEmpty()
                    && !line.isEmpty() && Character.isWhitespace(line.charAt(0))) {
                item.append(' ').append(trimmed);
                continue;
            }

            flushItem(out, item);
            flushItem(out, item);
            listOpen = closeList(out, listOpen);
            paragraph.add(line);
        }

        if (inCode) {
            // An unterminated fence renders as code rather than being dropped: the content is a
            // proof of concept, and silently discarding the tail of one is worse than an open block.
            out.append("<pre class=\"md-code\"><code>").append(Html.text(code.toString()))
                    .append("</code></pre>");
        }
        flushItem(out, item);
        flushParagraph(out, paragraph);
        closeList(out, listOpen);
        return out.toString();
    }

    /** A one-line preview with no markup at all, for a table cell. */
    public static String plain(String source, int limit) {
        if (source == null) {
            return "";
        }
        String flat = source.replaceAll("[`*_>#\\[\\]()]", " ").replaceAll("\\s+", " ").strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    /**
     * Renders a pipe table starting at {@code from}, returning how many lines it consumed.
     *
     * <p>Zero if the shape is not a table — a line beginning with a pipe is not enough, because a
     * proof of concept legitimately contains one. The separator row is what makes it unambiguous.
     *
     * <p>Cells are rendered through {@link #inline}, so a payload inside a table cell is escaped by
     * the same path as everywhere else. There is no branch here that emits raw text.
     */
    private static int table(StringBuilder out, List<String> lines, int from) {
        if (from + 1 >= lines.size()) {
            return 0;
        }
        String separator = lines.get(from + 1).strip();
        if (!separator.matches("\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?")) {
            return 0;
        }
        List<String> headers = cells(lines.get(from));
        if (headers.isEmpty()) {
            return 0;
        }
        // Bounded: a table wider than the page is unreadable, and one taller than this is data that
        // belongs in an attachment rather than in a comment.
        int maxColumns = Math.min(headers.size(), 12);

        out.append("<div class=\"md-table-wrap\"><table class=\"md-table\"><thead><tr>");
        for (int i = 0; i < maxColumns; i++) {
            out.append("<th scope=\"col\">").append(inline(headers.get(i))).append("</th>");
        }
        out.append("</tr></thead><tbody>");

        int row = from + 2;
        int rendered = 0;
        while (row < lines.size() && lines.get(row).strip().startsWith("|") && rendered < 200) {
            List<String> values = cells(lines.get(row));
            out.append("<tr>");
            for (int i = 0; i < maxColumns; i++) {
                out.append("<td>")
                        .append(i < values.size() ? inline(values.get(i)) : "")
                        .append("</td>");
            }
            out.append("</tr>");
            row++;
            rendered++;
        }
        out.append("</tbody></table></div>");
        return row - from;
    }

    private static List<String> cells(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static int headingLevel(String line) {
        int hashes = 0;
        while (hashes < line.length() && line.charAt(hashes) == '#') {
            hashes++;
        }
        return hashes > 0 && hashes <= 4 && hashes < line.length()
                && line.charAt(hashes) == ' ' ? hashes : 0;
    }

    /**
     * A stable, addressable id for a heading.
     *
     * <p>Derived from the heading's own text so a link survives an edit elsewhere in the document — a
     * positional id would move every anchor below an inserted section, and shared links would silently
     * point at the wrong place.
     *
     * <p><b>Only {@code [a-z0-9-]} survives, and the prefix is not optional.</b> The text is
     * attacker-authored on every surface but this one, and this value is written into an attribute
     * rather than into text, where the escaping the rest of this renderer relies on does not apply. So
     * the id is not escaped, it is CONSTRUCTED: every character outside the set is replaced, and the
     * prefix guarantees the result is a valid identifier even if nothing survives.
     *
     * @param used ids already emitted in this document; a repeat gets a numeric suffix, because two
     *     elements with one id makes the second unreachable and browsers pick the first silently
     */
    private static String slug(String text, java.util.Set<String> used) {
        StringBuilder cleaned = new StringBuilder(text.length() + 2);
        boolean lastWasDash = true;
        for (int i = 0; i < text.length() && cleaned.length() < 60; i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                cleaned.append(c);
                lastWasDash = false;
            } else if (!lastWasDash) {
                cleaned.append('-');
                lastWasDash = true;
            }
        }
        while (cleaned.length() > 0 && cleaned.charAt(cleaned.length() - 1) == '-') {
            cleaned.setLength(cleaned.length() - 1);
        }
        String base = "h-" + (cleaned.length() == 0 ? "section" : cleaned);
        String candidate = base;
        for (int n = 2; !used.add(candidate); n++) {
            candidate = base + "-" + n;
        }
        return candidate;
    }

    private static String closeList(StringBuilder out, String listOpen) {
        if (listOpen != null) {
            out.append("</").append(listOpen).append('>');
        }
        return null;
    }

    /**
     * Emits the buffered list item, if there is one.
     *
     * <p>An item is buffered rather than written as it is read, because its continuation lines arrive
     * afterwards and inline markup has to be parsed over the whole item — a `*phrase*` that wraps is
     * one phrase, and parsing the halves separately renders the asterisks.
     */
    private static void flushItem(StringBuilder out, StringBuilder item) {
        if (item.length() == 0) {
            return;
        }
        out.append("<li>").append(inline(item.toString())).append("</li>");
        item.setLength(0);
    }

    private static void flushParagraph(StringBuilder out, List<String> paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        out.append("<p class=\"md-p\">").append(inline(String.join(" ", paragraph))).append("</p>");
        paragraph.clear();
    }

    /**
     * Inline formatting.
     *
     * <p>The input is escaped FIRST and the markup is introduced afterwards, so no branch here can
     * emit a fragment of the original text unescaped. Doing it the other way — recognising markup in
     * raw text and escaping the remainder — is how a renderer grows a hole.
     */
    private static String inline(String raw) {
        // Character references are resolved BEFORE escaping, so anything they decode to is escaped
        // by the line below like any other text. Resolving them afterwards would turn "&#x3C;script"
        // into a tag on a page that had already been escaped, which is the hole this ordering exists
        // to close.
        String escaped = Html.text(decodeReferences(raw));

        StringBuilder out = new StringBuilder(escaped.length() + 32);
        int i = 0;
        while (i < escaped.length()) {
            char c = escaped.charAt(i);

            // A backslash escape. FIRST, before any marker is recognised, because that is what the
            // escape is for — it exists to stop the next character being read as a marker.
            //
            // This was missing, and the effect was not subtle. An editor that stores Markdown escapes
            // the punctuation a writer typed literally, so `user_id` arrives as `user\_id`. Without
            // this branch the backslash rendered as itself and the underscore still opened emphasis:
            // "snake\_case\_word" came out as "snake\<em>case\</em>word". Identifiers with
            // underscores are ubiquitous in a finding write-up, so almost any technical comment that
            // also used bold or italic looked corrupted after the first formatted run.
            if (c == '\\' && i + 1 < escaped.length()) {
                int consumed = unescape(escaped, i, out);
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
            }

            if (c == '`') {
                int end = escaped.indexOf('`', i + 1);
                if (end > i) {
                    out.append("<code class=\"md-inline\">")
                            .append(escaped, i + 1, end).append("</code>");
                    i = end + 1;
                    continue;
                }
            }
            // An image, and ONLY one this platform stored. `![alt](/ui/attachments/<uuid>)` — the
            // path shape is the allowlist, so an external URL cannot be smuggled through the image
            // syntax any more than through the link syntax. An <img> pointing at somebody else's host
            // is an exfiltration beacon that fires when a reviewer opens the finding and reports that
            // they read it, and who.
            if (c == '!' && i + 1 < escaped.length() && escaped.charAt(i + 1) == '[') {
                int close = escaped.indexOf(']', i);
                if (close > i && close + 1 < escaped.length() && escaped.charAt(close + 1) == '(') {
                    int end = escaped.indexOf(')', close);
                    if (end > close) {
                        String alt = escaped.substring(i + 2, close);
                        String src = escaped.substring(close + 2, end).strip();
                        out.append(image(src, alt));
                        i = end + 1;
                        continue;
                    }
                }
            }
            if (c == '[') {
                int close = escaped.indexOf(']', i);
                if (close > i && close + 1 < escaped.length() && escaped.charAt(close + 1) == '(') {
                    int end = escaped.indexOf(')', close);
                    if (end > close) {
                        String label = escaped.substring(i + 1, close);
                        String href = escaped.substring(close + 2, end).strip();
                        out.append(link(href, label));
                        i = end + 1;
                        continue;
                    }
                }
            }
            if (escaped.startsWith("**", i)) {
                int end = escaped.indexOf("**", i + 2);
                if (end > i) {
                    out.append("<strong>").append(escaped, i + 2, end).append("</strong>");
                    i = end + 2;
                    continue;
                }
            }
            // Single-asterisk emphasis. `**strong**` is tested first above, so a lone `*` reaching here
            // is emphasis and not the start of one.
            //
            // ADDED, because it was missing and the guide was the document that showed it: `*Not
            // measured*` rendered its asterisks literally on a help page, which is the one page a
            // reader is least equipped to look past. `_em_` worked, so nothing in the short comments
            // this renderer was built for had ever exercised the other spelling.
            //
            // The opening marker must be followed by a non-space, which is CommonMark's left-flanking
            // rule and is what keeps arithmetic — `2 * 3 * 4` — from becoming emphasis. `_` does not
            // carry that guard and is left as it is: changing it would alter how existing comments
            // render, and a renderer that renders yesterday's records differently is its own defect.
            if (c == '*' && i + 1 < escaped.length() && !Character.isWhitespace(escaped.charAt(i + 1))) {
                int end = escaped.indexOf('*', i + 1);
                if (end > i + 1) {
                    out.append("<em>").append(escaped, i + 1, end).append("</em>");
                    i = end + 1;
                    continue;
                }
            }
            if (c == '_') {
                int end = escaped.indexOf('_', i + 1);
                if (end > i + 1) {
                    out.append("<em>").append(escaped, i + 1, end).append("</em>");
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * The named references worth resolving.
     *
     * <p>HTML5 defines about two thousand. A closed set is carried instead, because an unknown name
     * stays literal and a reader sees {@code &farcical;} rather than nothing — whereas a full table
     * embedded here would be two thousand lines nobody reviews, guarding a case that does not arise:
     * the editor emits numeric references, and these are the names a person types by hand.
     */
    private static final Map<String, String> NAMED_REFERENCES = Map.ofEntries(
            Map.entry("nbsp", " "), Map.entry("amp", "&"), Map.entry("lt", "<"),
            Map.entry("gt", ">"), Map.entry("quot", "\""), Map.entry("apos", "'"),
            Map.entry("hellip", "…"), Map.entry("mdash", "—"),
            Map.entry("ndash", "–"), Map.entry("copy", "©"),
            Map.entry("reg", "®"), Map.entry("trade", "™"),
            Map.entry("laquo", "«"), Map.entry("raquo", "»"),
            Map.entry("times", "×"), Map.entry("middot", "·"));

    /**
     * Resolves HTML character references, as CommonMark requires.
     *
     * <h2>Why this is not optional, and what it fixes</h2>
     *
     * <p>CKEditor writes them into the Markdown it stores. A space it holds as a non-breaking space
     * comes out as {@code &#xA0;}, and the character immediately after a bold run comes out numeric
     * so it cannot be re-read as part of the emphasis — {@code **abc&#x20;**&#x64;êc}. The renderer
     * escaped the ampersand and printed the reference, so a comment reading "tôi là" was displayed as
     * <b>{@code tôi&#xA0;&#x20;là}</b> and one reading "abc dêc" lost its "d". Almost every comment
     * written in the editor contained at least one.
     *
     * <h2>A decoded character is text, never syntax</h2>
     *
     * <p>CommonMark is explicit that a reference produces a literal character, so {@code &#42;} is an
     * asterisk and not the start of emphasis. That is enforced by writing a backslash in front of any
     * decoded character the parser would otherwise treat as a marker, which the escape branch then
     * turns back into the plain character. Tracking provenance through the parser would be the other
     * way to do it and a far larger change for the same outcome.
     *
     * <p>A reference the writer escaped — {@code \&#x20;} — is left alone here so the escape branch
     * can render it literally. Consuming it would make the escape unusable.
     */
    private static String decodeReferences(String raw) {
        if (raw == null || raw.indexOf('&') < 0) {
            return raw == null ? "" : raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            // Preceded by an ODD number of backslashes means this ampersand is escaped.
            int backslashes = 0;
            for (int back = i - 1; back >= 0 && raw.charAt(back) == '\\'; back--) {
                backslashes++;
            }
            int end = raw.indexOf(';', i + 1);
            String decoded = null;
            // A reference is short. Bounding the search also stops an unterminated ampersand
            // swallowing the rest of a paragraph looking for a semicolon that belongs to prose.
            if (backslashes % 2 == 0 && end > i + 1 && end - i <= 12) {
                String name = raw.substring(i + 1, end);
                if (name.startsWith("#")) {
                    decoded = numeric(name.substring(1));
                } else {
                    decoded = NAMED_REFERENCES.get(name);
                }
            }
            if (decoded == null) {
                out.append(c);
                i++;
                continue;
            }
            for (int index = 0; index < decoded.length(); index++) {
                char ch = decoded.charAt(index);
                if (ESCAPABLE.indexOf(ch) >= 0) {
                    out.append('\\');
                }
                out.append(ch);
            }
            i = end + 1;
        }
        return out.toString();
    }

    /** A numeric reference, decimal or hexadecimal, or null where it is not one. */
    private static String numeric(String digits) {
        boolean hex = !digits.isEmpty() && (digits.charAt(0) == 'x' || digits.charAt(0) == 'X');
        String body = hex ? digits.substring(1) : digits;
        if (body.isEmpty() || body.length() > 6) {
            return null;
        }
        int code;
        try {
            code = Integer.parseInt(body, hex ? 16 : 10);
        } catch (NumberFormatException e) {
            return null;
        }
        // CommonMark maps an invalid code point to U+FFFD. NUL and the surrogate range are excluded
        // for the same reason: a lone surrogate in a string is a corruption that surfaces much later,
        // somewhere with no connection to the comment that carried it.
        if (code == 0 || code > 0x10FFFF || (code >= 0xD800 && code <= 0xDFFF)) {
            return "�";
        }
        return new String(Character.toChars(code));
    }

    /**
     * The punctuation a backslash may escape.
     *
     * <p>CommonMark's set. A backslash before anything else is a literal backslash, which is why this
     * is an allowlist rather than "consume whatever follows": {@code C:\temp} and {@code \d+} in a
     * regular expression are both ordinary text in a finding write-up, and swallowing the backslash
     * would silently rewrite somebody's evidence.
     */
    private static final String ESCAPABLE = "\\`*_{}[]()#+-.!>|~=\"'&$%,/:;?@^";

    /**
     * The HTML entities {@link Html#text} produces, longest first.
     *
     * <p>Escaping runs before this parser, so a backslash-escaped {@code <} arrives as {@code \&lt;}
     * and the character after the backslash is an ampersand rather than the character the writer
     * escaped. Matching the whole entity is what keeps {@code \<script>} rendering as visible text
     * instead of a stray backslash followed by an entity.
     */
    private static final List<String> ENTITIES =
            List.of("&quot;", "&amp;", "&#39;", "&lt;", "&gt;");

    /**
     * Writes the escaped character and reports how much input it used, or 0 if this is not an escape.
     *
     * @param at the index of the backslash
     */
    private static int unescape(String escaped, int at, StringBuilder out) {
        char next = escaped.charAt(at + 1);
        if (next == '&') {
            for (String entity : ENTITIES) {
                if (escaped.startsWith(entity, at + 1)) {
                    out.append(entity);
                    return 1 + entity.length();
                }
            }
            // A bare ampersand cannot occur — Html.text turns every one into &amp; — so reaching here
            // means the text was not escaped by it, and consuming the backslash would be a guess.
            return 0;
        }
        if (ESCAPABLE.indexOf(next) >= 0) {
            out.append(next);
            return 2;
        }
        return 0;
    }

    /**
     * An image, or its alt text where the source is not one this platform stored.
     *
     * <p>The permitted shape is exactly {@code /attachments/<uuid>}, and {@code /ui/attachments/<uuid>}
     * is accepted as well and rewritten to it. That second form is not politeness: comment bodies stored
     * before the interface moved to the root hold it, a comment body is a record and is not rewritten to
     * suit a routing change, and without this the images in those write-ups would silently render as
     * alt text. Anything else renders as the alt text with the source beside it as inert code — the
     * reader still sees what it pointed at, which matters when the URL is itself part of the finding.
     *
     * <p>{@code loading="lazy"} and an explicit {@code alt} are not decoration: a write-up with twenty
     * screenshots should not fetch twenty images to show its first paragraph, and an image with no
     * alt text is a gap in the report for anybody reading it with a screen reader.
     */
    private static String image(String src, String alt) {
        String canonical = src.startsWith("/ui/attachments/") ? src.substring(3) : src;
        if (!canonical.matches("/attachments/[0-9a-fA-F-]{36}")) {
            return "<span>" + alt + "</span> <code class=\"md-inline\">" + src + "</code>";
        }
        // The CANONICAL path, so a stored legacy link costs no redirect and the emitted document says
        // where the bytes are now rather than where they used to be.
        return "<img class=\"md-image\" loading=\"lazy\" alt=\"" + alt + "\" src=\"" + canonical + "\">";
    }

    /**
     * A link, or the label alone where the scheme is not one a reader should be able to trigger.
     *
     * <p>An allowlist of two schemes. {@code javascript:} is script execution wearing a link and
     * {@code data:} smuggles arbitrary content past every other rule. A rejected link renders as its
     * label followed by the URL as inert text — the reader still sees what it pointed at, which
     * matters when the URL is itself part of the finding.
     */
    private static String link(String href, String label) {
        String lower = href.toLowerCase(java.util.Locale.ROOT);
        boolean permitted = lower.startsWith("http://") || lower.startsWith("https://")
                // A path within this platform. Not "//", which is protocol-relative and resolves to
                // another host — when the interface lived under /ui/ that shape could not reach here.
                || (lower.startsWith("/") && !lower.startsWith("//"));
        if (!permitted) {
            return "<span>" + label + "</span> <code class=\"md-inline\">" + href + "</code>";
        }
        // rel on every link. A write-up that opens a tab holding a window handle back to this
        // application hands the linked page a reference it can navigate — and the linked page in a
        // finding is, often enough, the attacker's.
        return "<a class=\"link\" rel=\"noopener noreferrer nofollow\" href=\"" + href + "\">"
                + label + "</a>";
    }
}
