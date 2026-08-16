package aspm.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The restricted Markdown renderer. {@code PRD-ASM-012}, {@code SEC-SEC-032}.
 *
 * <p>This class had no tests, which is the wrong state for the one component that decides what markup
 * a finding write-up may become. Everything it renders is attacker-influenced by design: a finding's
 * description carries whatever was in the application under test, and an imported finding carries
 * whatever the scanner reported.
 */
class MarkdownTest {

    @Nested
    @DisplayName("Emphasis, and the escapes an editor writes around it")
    class Emphasis {

        @Test
        @DisplayName("bold and italic render")
        void emphasisRenders() {
            assertEquals("<p class=\"md-p\">a <strong>b</strong> c</p>",
                    Markdown.render("a **b** c"));
            assertEquals("<p class=\"md-p\">a <em>b</em> c</p>", Markdown.render("a _b_ c"));
        }

        /**
         * The regression this class was written for.
         *
         * <p>CKEditor stores Markdown, so punctuation a writer typed literally arrives escaped:
         * {@code user_id} is sent as {@code user\_id}. The renderer did not implement backslash
         * escapes, so the backslash printed and the underscore still opened emphasis —
         * {@code snake\_case\_word} rendered as {@code snake\<em>case\</em>word}.
         *
         * <p>Identifiers with underscores are everywhere in a write-up, so in practice any technical
         * comment that also used bold or italic looked corrupted from the first formatted run onward.
         */
        @Test
        @DisplayName("a backslash-escaped underscore is a literal underscore, not emphasis")
        void backslashEscapesAreHonoured() {
            String rendered = Markdown.render("plain **bolded** then _slanted_ with snake\\_case\\_word");
            assertEquals("<p class=\"md-p\">plain <strong>bolded</strong> then <em>slanted</em> "
                    + "with snake_case_word</p>", rendered);
            assertFalse(rendered.contains("\\"), "the backslash must be consumed, not printed");
        }

        @Test
        @DisplayName("a backslash before a non-escapable character stays a backslash")
        void aLiteralBackslashSurvives() {
            // C:\temp and \d+ are ordinary text in a finding. Consuming the backslash whatever
            // follows it would silently rewrite somebody's evidence.
            assertTrue(Markdown.render("path C:\\temp and regex \\d+")
                    .contains("C:\\temp and regex \\d+"));
        }

        @Test
        @DisplayName("an escaped angle bracket renders as visible text, still escaped")
        void anEscapedEntityIsWrittenWhole() {
            // Html escaping runs first, so the writer's \< arrived as \&lt; and the character after
            // the backslash is an ampersand rather than the one they escaped.
            String rendered = Markdown.render("literal \\<script\\> here");
            assertTrue(rendered.contains("&lt;script&gt;"), rendered);
            assertFalse(rendered.contains("<script"), "an actual tag must never appear: " + rendered);
        }
    }

    @Nested
    @DisplayName("HTML character references, which the editor writes into the Markdown")
    class CharacterReferences {

        /**
         * The reported defect. A comment reading "tôi là" was stored by the editor as
         * {@code **tôi&#xA0;**&#x20;là} and displayed with the references printed as text.
         */
        @Test
        @DisplayName("a numeric reference becomes its character, not its own source text")
        void numericReferencesAreResolved() {
            assertEquals("<p class=\"md-p\"><strong>tôi </strong> là</p>",
                    Markdown.render("**tôi&#xA0;**&#x20;là"));
            // The editor emits the character after a bold run numerically so it cannot be re-read as
            // emphasis. Printed rather than decoded, that comment lost its "d": "abc" then "êc".
            assertEquals("<p class=\"md-p\"><strong>abc </strong>dêc</p>",
                    Markdown.render("**abc&#x20;**&#x64;êc"));
        }

        @Test
        @DisplayName("a named reference is resolved from a closed set; an unknown one stays literal")
        void namedReferencesAreResolved() {
            assertTrue(Markdown.render("a&nbsp;b").contains("a b"));
            assertTrue(Markdown.render("&copy; 2026").contains("© 2026"));
            String unknown = Markdown.render("&farcical; thing");
            assertTrue(unknown.contains("&amp;farcical;"),
                    "an unknown name stays visible so the reader sees what was written: " + unknown);
        }

        /**
         * The property that makes decoding safe to do at all.
         *
         * <p>CommonMark: a reference produces a LITERAL character. An asterisk that arrived as
         * {@code &#42;} must not open emphasis, or a comment could smuggle markup past a writer who
         * typed none.
         */
        @Test
        @DisplayName("a decoded character is text, never Markdown syntax")
        void decodedCharactersAreNotSyntax() {
            String rendered = Markdown.render("&#42;not bold&#42;");
            assertEquals("<p class=\"md-p\">*not bold*</p>", rendered);
        }

        @Test
        @DisplayName("a reference decoding to markup is escaped, not rendered")
        void aDecodedTagIsStillText() {
            // The ordering that matters: references resolve BEFORE escaping, so this is escaped like
            // any other text. Resolving afterwards would put a tag on an already-escaped page.
            String rendered = Markdown.render("&#x3C;script&#x3E;alert(1)&#x3C;/script&#x3E;");
            assertFalse(rendered.contains("<script"), rendered);
            assertTrue(rendered.contains("&lt;script&gt;"), rendered);
        }

        @Test
        @DisplayName("an escaped reference is left as text")
        void anEscapedReferenceIsNotDecoded() {
            assertTrue(Markdown.render("literal \\&#x20; here").contains("&amp;#x20;"));
        }

        @Test
        @DisplayName("an unterminated ampersand does not swallow the paragraph")
        void anUnterminatedReferenceIsHarmless() {
            String rendered = Markdown.render("Tom & Jerry; and more prose after it");
            assertTrue(rendered.contains("Tom &amp; Jerry; and more prose after it"), rendered);
        }
    }

    @Nested
    @DisplayName("What the renderer refuses")
    class Refusals {

        @Test
        @DisplayName("raw HTML is text, never markup")
        void rawHtmlIsEscaped() {
            String rendered = Markdown.render("<img src=x onerror=alert(1)>");
            assertFalse(rendered.contains("<img src=x"), rendered);
            assertTrue(rendered.contains("&lt;img"), rendered);
        }

        /**
         * <p>A known limit is asserted here rather than worked around: the target ends at the first
         * {@code )}, so a URL containing one is truncated in the inert text. It does not weaken the
         * refusal — the scheme test runs on the truncated string and everything after it is plain
         * text — but a reader of a link to a page with brackets in its path sees a shortened URL.
         */
        @Test
        @DisplayName("a javascript: link renders as inert text with its target visible")
        void aScriptSchemeIsRefused() {
            String rendered = Markdown.render("[click](javascript:alert&#39;x&#39;)");
            assertFalse(rendered.contains("href=\"javascript:"), rendered);
            assertTrue(rendered.contains("javascript:alert"),
                    "the reader still has to see what it pointed at: " + rendered);
        }

        @Test
        @DisplayName("a permitted link carries rel on every one of them")
        void aPermittedLinkIsConstrained() {
            String rendered = Markdown.render("[x](https://example.test/a)");
            assertTrue(rendered.contains("rel=\"noopener noreferrer nofollow\""), rendered);
        }

        @Test
        @DisplayName("an image from anywhere but the attachment store is not an image")
        void anExternalImageIsRefused() {
            // An <img> pointing at somebody else's host is a beacon that fires when a reviewer opens
            // the finding, and reports that they read it and who.
            String rendered = Markdown.render("![shot](https://evil.test/pixel.png)");
            assertFalse(rendered.contains("<img"), rendered);
            assertTrue(rendered.contains("evil.test/pixel.png"), rendered);
        }

        @Test
        @DisplayName("a stored attachment does render")
        void aStoredImageRenders() {
            String rendered = Markdown.render(
                    "![shot](/ui/attachments/019fd561-ef93-73ce-be41-7579e671a157)");
            assertTrue(rendered.contains("<img class=\"md-image\""), rendered);
            assertTrue(rendered.contains("alt=\"shot\""), rendered);
        }
    }

    @Test
    @DisplayName("a wrapped list item stays one item, and its markup is parsed over the whole item")
    void wrappedListItemsStayWhole() {
        // Both halves of this were visible on the first long document this renderer was given. The
        // continuation became a paragraph of its own, and the emphasis that spanned the wrap rendered
        // its asterisks literally because each half was parsed separately.
        String rendered = Markdown.render("""
                - **Measured** is a number, and *unmeasured* carries no
                  numeral at all — you will see *words* instead.
                - A second item.
                """);
        assertEquals(1, rendered.split("<ul", -1).length - 1, "one list: " + rendered);
        assertEquals(2, rendered.split("<li>", -1).length - 1, "two items: " + rendered);
        assertTrue(rendered.contains("numeral at all"), "the continuation belongs to its item");
        assertFalse(rendered.contains("*words*"),
                "emphasis spanning the wrap must be parsed, not printed: " + rendered);
        assertTrue(rendered.contains("<em>words</em>"), rendered);
    }

    @Test
    @DisplayName("a paragraph after a list is still a paragraph")
    void anUnindentedLineEndsTheList() {
        // The continuation rule requires indentation for exactly this reason: a list followed by prose
        // is the commonest shape in the guide, and swallowing that prose into the last bullet would be
        // a worse defect than the one being fixed.
        String rendered = Markdown.render("""
                - One item.
                This follows the list.
                """);
        assertEquals(1, rendered.split("<li>", -1).length - 1, rendered);
        assertTrue(rendered.contains("</ul>"), rendered);
        assertTrue(rendered.contains("<p class=\"md-p\">This follows the list.</p>"), rendered);
    }

    @Test
    @DisplayName("single-asterisk emphasis renders, and arithmetic does not")
    void singleAsteriskEmphasis() {
        assertTrue(Markdown.render("A *word* here.").contains("<em>word</em>"));
        // The left-flanking guard. Without it a sentence about multiplication becomes emphasis, and
        // the reader is left wondering what the platform is stressing.
        assertFalse(Markdown.render("2 * 3 * 4").contains("<em>"));
        // ** is still strong, not two emphases.
        String strong = Markdown.render("**both** and *one*");
        assertTrue(strong.contains("<strong>both</strong>"), strong);
        assertTrue(strong.contains("<em>one</em>"), strong);
    }

    @Test
    @DisplayName("every heading is addressable, and the id is constructed rather than escaped")
    void headingsCarryIds() {
        String rendered = Markdown.render("## What the platform is for\n\n### Signing in\n");
        assertTrue(rendered.contains("id=\"h-what-the-platform-is-for\""), rendered);
        assertTrue(rendered.contains("id=\"h-signing-in\""), rendered);
    }

    @Test
    @DisplayName("a heading of hostile punctuation still yields a usable id")
    void headingIdsAreConstructed() {
        // The id is written into an ATTRIBUTE, where the escaping the rest of this renderer relies on
        // does not apply — so it is built from a permitted alphabet rather than cleaned afterwards.
        String rendered = Markdown.render("## \" onmouseover=alert(1) x=\"\n");
        var matcher = java.util.regex.Pattern.compile("id=\"([^\"]*)\"").matcher(rendered);
        assertTrue(matcher.find(), rendered);
        assertTrue(matcher.group(1).matches("h-[a-z0-9-]*"),
                "only [a-z0-9-] may survive: " + matcher.group(1));
        // The property that matters is that nothing can leave the attribute: no quote, no space, no
        // equals sign. The WORDS may survive inside the id — "h-onmouseover-alert-1-x" is inert, and
        // asserting their absence would be asserting something this defence does not depend on.
        assertFalse(matcher.group(1).contains("\""), matcher.group(1));
        assertFalse(matcher.group(1).contains(" "), matcher.group(1));
        assertFalse(matcher.group(1).contains("="), matcher.group(1));
        // And the heading TEXT is escaped, as every other piece of prose is.
        assertTrue(rendered.contains("&quot;"), rendered);
    }

    @Test
    @DisplayName("two headings with the same title get two different ids")
    void duplicateHeadingsAreDisambiguated() {
        // Two elements with one id makes the second unreachable, and the browser picks the first
        // without saying so — a contents entry that always jumps to the wrong section.
        String rendered = Markdown.render("## Overview\n\n## Overview\n");
        assertTrue(rendered.contains("id=\"h-overview\""), rendered);
        assertTrue(rendered.contains("id=\"h-overview-2\""), rendered);
    }
}
