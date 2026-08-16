package aspm.module.workmanagement.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Comment body content. {@code INV-WRK-10}: <b>a constrained allowlist, never sanitized arbitrary markup.</b>
 *
 * <p>{@code PRD-WRK-019}'s security note states the threat plainly: "Comment content is user input rendered to
 * other users and is a stored cross-site scripting vector; rich text MUST be a constrained allowlist rather than
 * sanitized arbitrary markup."
 *
 * <h2>Why an allowlist rather than sanitization, stated once and properly</h2>
 *
 * <p>Sanitization takes arbitrary markup and tries to remove what is dangerous. It is a denylist wearing a
 * different name, and it fails the same way: every sanitizer in wide use has a history of bypasses, because the
 * input space is HTML and the attacker gets to explore all of it. An allowlist inverts the problem — the document
 * is a small closed set of node types and nothing else can be represented, so a bypass would require a defect in
 * a parser over a grammar with seven productions rather than a gap in a filter over an open one.
 *
 * <p>The practical consequence is that this type <b>does not accept an HTML string</b>. There is no constructor
 * taking markup and no {@code sanitize} method, because either would put the open input space back. Content
 * arrives as a node list, and the renderer emits markup — the direction of the conversion is the control.
 *
 * <p><b>The cost, stated.</b> Everything the allowlist does not cover is unavailable: tables, images, arbitrary
 * links with attributes, embedded HTML from an incumbent tracker's export. Migration import (ADR-028) will meet
 * markup this cannot represent, and the answer there is a lossy conversion that records what it dropped — not a
 * widening of this grammar. A comment that renders slightly worse is a smaller problem than a comment that runs.
 *
 * <p>Note also {@code PRD-AIC-008}: comment content is excluded from AI prompt context unless the tenant has
 * explicitly permitted it, "since comments routinely contain material users did not consider sensitive". That is
 * a separate control living in the AI module, and it is why {@link #plainText()} exists as a named, greppable
 * call site rather than as an incidental {@code toString}.
 */
public final class ConstrainedRichText {

    /** The permitted node kinds. Adding one is a security review, not a feature toggle. */
    public sealed interface Node {

        /** Literal text. Never interpreted as markup by the renderer. */
        record Text(String value) implements Node {
            public Text {
                Objects.requireNonNull(value, "text is required");
            }
        }

        /** A paragraph of inline nodes. */
        record Paragraph(List<Node> children) implements Node {
            public Paragraph {
                children = List.copyOf(Objects.requireNonNull(children, "children are required"));
            }
        }

        /** Bold. */
        record Strong(List<Node> children) implements Node {
            public Strong {
                children = List.copyOf(Objects.requireNonNull(children, "children are required"));
            }
        }

        /** Italic. */
        record Emphasis(List<Node> children) implements Node {
            public Emphasis {
                children = List.copyOf(Objects.requireNonNull(children, "children are required"));
            }
        }

        /**
         * Inline or block code. {@code PRD-WRK-019} names code formatting explicitly.
         *
         * <p>The one node whose content is guaranteed never to be interpreted: a security discussion routinely
         * pastes a payload, and a comment field that mangled or executed it would be unusable for the work this
         * platform exists to support.
         *
         * @param language a hint for highlighting. Constrained to a short identifier, because it reaches a class
         *     attribute in the rendered output
         */
        record Code(String content, String language, boolean block) implements Node {
            public Code {
                Objects.requireNonNull(content, "code content is required");
                Objects.requireNonNull(language, "a language is required, empty where unknown");
                if (!language.isEmpty() && !language.matches("[a-zA-Z0-9_+-]{1,24}")) {
                    throw new IllegalArgumentException(
                            "language '" + language + "' is not a short identifier. It reaches a class "
                                    + "attribute in the rendered output, so an unconstrained value is an "
                                    + "injection point in the one node whose content is deliberately not "
                                    + "escaped.");
                }
            }
        }

        /** An ordered or unordered list. */
        record ItemList(List<Paragraph> items, boolean ordered) implements Node {
            public ItemList {
                items = List.copyOf(Objects.requireNonNull(items, "items are required"));
            }
        }

        /**
         * A mention of a principal.
         *
         * <p>Carries the identifier, not a display name: a name copied into the body at authoring time would go
         * stale, and a stale mention in audit evidence reads as a claim about who was involved.
         *
         * <p>Whether the mention may be <b>rendered</b> to a given reader is a separate question from whether it
         * may be <b>authored</b> — see {@link MentionResolution} for the second and
         * {@code INV-WRK-09} for why both matter.
         */
        record Mention(java.util.UUID principalId) implements Node {
            public Mention {
                Objects.requireNonNull(principalId, "a principal identifier is required");
            }
        }

        /**
         * A reference to another work item by code.
         *
         * <p>Not a general link. An arbitrary URL in a comment is a phishing vector inside a trusted surface, and
         * this platform's readers are the population {@code PP-7} describes as having the narrowest permissions
         * and the least training. A reference resolves within the platform or renders as plain text.
         */
        record ItemReference(String itemCode) implements Node {
            public ItemReference {
                Objects.requireNonNull(itemCode, "an item code is required");
                if (!itemCode.matches("[A-Z][A-Z0-9]{0,15}-[0-9]{1,9}")) {
                    throw new IllegalArgumentException("'" + itemCode + "' is not an item code");
                }
            }
        }
    }

    /** Depth bound. A deeply nested document is a renderer stack-overflow vector, not a formatting choice. */
    public static final int MAX_DEPTH = 8;

    /** Node-count bound, so one comment cannot make an item view unloadable for everyone who opens it. */
    public static final int MAX_NODES = 2_000;

    private final List<Node> nodes;

    private ConstrainedRichText(List<Node> nodes) {
        this.nodes = nodes;
    }

    public static ConstrainedRichText of(List<Node> nodes) {
        Objects.requireNonNull(nodes, "nodes are required");
        int count = countAndCheckDepth(nodes, 1);
        if (count > MAX_NODES) {
            throw new IllegalArgumentException(
                    "a comment of " + count + " nodes exceeds " + MAX_NODES + "; one comment must not make an "
                            + "item view unloadable for everyone who opens it");
        }
        return new ConstrainedRichText(List.copyOf(nodes));
    }

    /** The marker body a redacted comment carries. See {@link Comment#redact}. */
    public static ConstrainedRichText redactionMarker() {
        return of(List.of(new Node.Paragraph(List.of(
                new Node.Text("[redacted — the original is retained in the revision history]")))));
    }

    private static int countAndCheckDepth(List<? extends Node> nodes, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "nesting deeper than " + MAX_DEPTH + "; a deeply nested document is a renderer "
                            + "stack-overflow vector rather than a formatting choice");
        }
        int count = 0;
        for (Node node : nodes) {
            count++;
            count += switch (node) {
                case Node.Paragraph p -> countAndCheckDepth(p.children(), depth + 1);
                case Node.Strong s -> countAndCheckDepth(s.children(), depth + 1);
                case Node.Emphasis e -> countAndCheckDepth(e.children(), depth + 1);
                case Node.ItemList l -> countAndCheckDepth(l.items(), depth + 1);
                case Node.Text t -> 0;
                case Node.Code c -> 0;
                case Node.Mention m -> 0;
                case Node.ItemReference r -> 0;
            };
        }
        return count;
    }

    public List<Node> nodes() {
        return nodes;
    }

    /** Every principal mentioned, in document order without duplicates. */
    public Set<java.util.UUID> mentionedPrincipals() {
        Set<java.util.UUID> found = new LinkedHashSet<>();
        collectMentions(nodes, found);
        return Set.copyOf(found);
    }

    private static void collectMentions(List<? extends Node> nodes, Set<java.util.UUID> into) {
        for (Node node : nodes) {
            switch (node) {
                case Node.Mention m -> into.add(m.principalId());
                case Node.Paragraph p -> collectMentions(p.children(), into);
                case Node.Strong s -> collectMentions(s.children(), into);
                case Node.Emphasis e -> collectMentions(e.children(), into);
                case Node.ItemList l -> collectMentions(l.items(), into);
                default -> {
                    // Text, Code, ItemReference carry no mentions.
                }
            }
        }
    }

    /**
     * The text content, for search indexing and for the AI-context exclusion decision of {@code PRD-AIC-008}.
     *
     * <p>A named method rather than {@code toString} so that every place comment text leaves this type is
     * greppable. "Which code paths can send a comment to a model" is a question somebody will have to answer.
     */
    public String plainText() {
        StringBuilder out = new StringBuilder();
        appendText(nodes, out);
        return out.toString().strip();
    }

    private static void appendText(List<? extends Node> nodes, StringBuilder out) {
        for (Node node : nodes) {
            switch (node) {
                case Node.Text t -> out.append(t.value());
                case Node.Code c -> out.append(c.content());
                case Node.ItemReference r -> out.append(r.itemCode());
                case Node.Mention m -> out.append('@');
                case Node.Paragraph p -> {
                    appendText(p.children(), out);
                    out.append('\n');
                }
                case Node.Strong s -> appendText(s.children(), out);
                case Node.Emphasis e -> appendText(e.children(), out);
                case Node.ItemList l -> {
                    for (Node.Paragraph item : l.items()) {
                        appendText(item.children(), out);
                        out.append('\n');
                    }
                }
            }
        }
    }

    public boolean isEmpty() {
        return plainText().isBlank() && mentionedPrincipals().isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConstrainedRichText t && nodes.equals(t.nodes);
    }

    @Override
    public int hashCode() {
        return nodes.hashCode();
    }
}
