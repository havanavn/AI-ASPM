package aspm.app.ai;

import aspm.app.ui.GuidePage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The product's own documentation, made retrievable.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The copilot could answer "how many findings are open" and could not answer "how do I call the
 * API" or "how do I give the developers access", because every one of its packs was a database query
 * and neither of those questions has a row behind it. They have an answer — the user guide and the
 * integration guide, both written, both translated — and the copilot could not see them. Reported from
 * use on 2026-09-13.
 *
 * <h2>Why keyword retrieval and not embeddings</h2>
 *
 * <p>Four documents, about three hundred sections. A vector index would be a second store to keep in
 * step with the prose, a model call on the retrieval path, and a similarity score nobody can explain
 * when it picks the wrong section. Term overlap over the tenant's own vocabulary is deterministic,
 * explains itself — the matched words are in the heading the answer cites — and is measurably good
 * enough at this size. If the corpus grows by an order of magnitude the decision is worth reopening;
 * at four documents it would be engineering for an imagined problem.
 *
 * <h2>Why both languages are searched and neither is chosen in advance</h2>
 *
 * <p>A Vietnamese question scores against the Vietnamese translation because it shares its words. No
 * language detection is required and none is performed: the ranking does the work, and a question
 * mixing both — which is how people actually write about "API" and "SLA" — finds whichever document
 * says more about it. Diacritics are folded on both sides, because the language is typed without them.
 *
 * <h2>This is platform-authored text</h2>
 *
 * <p>So it goes into the FACTS channel, not through the fence of {@code PRD-AIC-037}. The fence is for
 * content somebody outside the platform wrote — a finding title, an asset name. Documentation the
 * product ships is the same class of material as the figures: ours, and safe to instruct with.
 */
public final class Knowledge {

    /** One heading and the prose under it, as the unit a citation can point at. */
    public record Section(String document, String locale, String heading, String body) {

        /** Where a reader goes to read the whole thing. */
        public String link() {
            return "api".equals(document) ? "/api-guide" : "/guide";
        }
    }

    public record Hit(Section section, int score) {
    }

    /**
     * Words that carry no subject in either language.
     *
     * <p>Folded, so the Vietnamese entries are written as they are matched. Kept short on purpose: an
     * aggressive stop list removes the word that made the question specific, and the cost of a stray
     * common word is one point of score, not a wrong section.
     *
     * <p>Built with {@code copyOf} rather than {@code Set.of} because the two languages collide once
     * folded — English "the" is also how "thế" folds, and "a" and "no" likewise — and {@code Set.of}
     * refuses a duplicate by throwing during class initialization, which is a startup failure rather
     * than a spelling mistake.
     */
    private static final Set<String> STOP = Set.copyOf(List.of(
            // English
            "the", "a", "an", "of", "to", "is", "are", "was", "how", "do", "does", "did", "i", "we",
            "what", "which", "can", "could", "my", "our", "for", "in", "on", "at", "and", "or", "with",
            "it", "this", "that", "these", "be", "been", "have", "has", "you", "your", "me", "please",
            "there", "any", "some", "all", "not", "no", "yes", "about", "from", "by", "as", "if",
            // Vietnamese, folded
            "lam", "sao", "the", "nao", "cua", "cho", "va", "co", "khong", "toi", "minh", "nhu", "de",
            "duoc", "mot", "cac", "nhung", "gi", "trong", "voi", "la", "thi", "se", "da", "bi", "tu",
            "hay", "hoac", "ve", "ra", "vao", "khi", "day", "kia", "nay", "no", "ai", "dau", "ma",
            "nhi", "ban", "muon", "can", "giup", "huong", "dan"));

    /** Built once: the documents are classpath resources and do not change while the process runs. */
    private static final List<Section> SECTIONS = index();

    private Knowledge() {
    }

    /** Every section of every document, for tests and for the count a caller may want to state. */
    public static List<Section> sections() {
        return SECTIONS;
    }

    /**
     * The sections most likely to answer this question.
     *
     * @param documents which documents to search — {@code "guide"}, {@code "api"}, or both
     * @param limit how many sections to return; three is about what fits in a prompt beside the figures
     */
    public static List<Hit> search(String question, Set<String> documents, int limit) {
        List<String> terms = terms(question);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (Section section : SECTIONS) {
            if (!documents.contains(section.document())) {
                continue;
            }
            int score = score(section, terms);
            if (score > 0) {
                hits.add(new Hit(section, score));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed()
                // A stable tie-break, so the same question returns the same sections every time and a
                // reader who checked a citation yesterday finds it where they left it.
                .thenComparing(h -> h.section().heading()));
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);
    }

    /**
     * Term overlap, heading weighted.
     *
     * <p>A term in the heading is worth five because a heading is the section's claim about itself: "8.
     * Creating the estate" answers "how do I create an application" more directly than a paragraph that
     * happens to use the word twice. Body matches are counted once per term rather than per occurrence,
     * so a long section does not outrank a precise one by repetition.
     */
    private static int score(Section section, List<String> terms) {
        String heading = Copilot.fold(section.heading());
        String body = Copilot.fold(section.body());
        int score = 0;
        for (String term : terms) {
            String padded = " " + term + " ";
            if (heading.contains(padded)) {
                score += 5;
            } else if (body.contains(padded)) {
                score += 1;
            }
        }
        return score;
    }

    /** The question's content words, folded, de-duplicated, two characters or more. */
    static List<String> terms(String question) {
        Set<String> out = new LinkedHashSet<>();
        for (String token : Copilot.fold(question).strip().split(" +")) {
            if (token.length() >= 2 && !STOP.contains(token)) {
                out.add(token);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Splits each document at its headings.
     *
     * <p>Both heading levels are sections. A {@code ###} under a {@code ##} is usually the answer to a
     * more specific question — "Signing a request" under "There are no API keys" — and rolling it into
     * its parent would return the parent's whole length to answer it.
     */
    private static List<Section> index() {
        List<Section> out = new ArrayList<>();
        for (Map.Entry<String, String> document : Map.of("guide", "guide", "api", "api").entrySet()) {
            for (String language : List.of("en", "vi")) {
                Locale locale = Locale.forLanguageTag(language);
                String source = "api".equals(document.getKey()) ? GuidePage.loadApi(locale) : GuidePage.load(locale);
                if (source == null || source.isBlank()) {
                    continue;
                }
                String heading = null;
                StringBuilder body = new StringBuilder();
                for (String line : source.split("\n", -1)) {
                    if (line.startsWith("## ") || line.startsWith("### ")) {
                        if (heading != null) {
                            out.add(new Section(document.getKey(), language, heading, body.toString().strip()));
                        }
                        heading = line.replaceFirst("^#+ ", "").strip();
                        body.setLength(0);
                    } else if (heading != null) {
                        body.append(line).append('\n');
                    }
                }
                if (heading != null) {
                    out.add(new Section(document.getKey(), language, heading, body.toString().strip()));
                }
            }
        }
        return List.copyOf(out);
    }
}
