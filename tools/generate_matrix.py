#!/usr/bin/env python3
"""Populate _traceability/matrix.csv and report the two release gates.

DOC-16 section 16.1 fixes the columns: requirement identifier, owning document, design
reference, schema reference, API reference, test case identifier, verification method,
status. "A non-applicable cell contains N/A with a one-line reason, never blank."

Three cell values, not two
--------------------------
The corpus asks for a value or `N/A` with a reason. That is not enough vocabulary to be
honest here, because two different things produce an empty cell:

  N/A (reason)      the requirement genuinely has no artifact of that kind. A conventions
                    requirement has no schema object; a documentation-verified requirement
                    has no test case.
  MISSING (reason)  the requirement should have one and does not. This is a GAP.

Collapsing the second into the first is how a traceability matrix comes to read as complete
while covering half the corpus, so the two are distinct tokens and the gates count only
`MISSING`. PRD-PLT-012's own rationale is the argument: forward traceability catches
under-delivery, and a matrix that cannot express under-delivery catches nothing.

What the evidence is
--------------------
Requirement identifiers cited in source. Every module written so far cites the requirement
it implements in the javadoc or the SQL comment, and every assertion cites the requirement
it asserts. That convention is what this tool reads; it is not a proof of correctness and
does not claim to be. A citation means somebody wrote the identifier next to the code, and
the gate it feeds is coverage, not conformance.

Granularity of "passing"
------------------------
Per test CLASS, from the JUnit XML. A requirement is covered when it is cited inside a test
method that is not @Disabled, in a class whose XML records zero failures and zero errors.
Method-level matching is not available: Gradle writes @DisplayName into the XML's `name`
attribute, so the method name is absent from the results. Stated rather than worked around,
because a matcher that silently fell back to the class would report a disabled method as
passing.
"""

from __future__ import annotations

import csv
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")
REGISTER = os.path.join(ROOT, "_traceability", "requirements.csv")
MATRIX = os.path.join(ROOT, "_traceability", "matrix.csv")
GAPS = os.path.join(ROOT, "_traceability", "GAPS.md")

# The register's own identifier shape. Anchored on a word boundary so ADR-036 and OQ-015 do
# not match, and so a longer identifier is not matched by a shorter prefix.
ID_PATTERN = re.compile(r"\b([A-Z]{3}-[A-Z]{3}-\d{3})\b")

DOC_SECTION = re.compile(r"\bDOC-\d{2}\s+section\s+[\d.]+", re.IGNORECASE)

# An invariant is a trace. CLAUDE.md: "the invariants ARE the specification", and DOC-03 owns them,
# so a trigger function citing INV-WRK-08 and nothing else is traced to the domain model. Omitting
# this pattern reported thirty-six objects as untraced; sixteen of them cited an invariant.
INVARIANT = re.compile(r"\bINV-[A-Z]{3}-\d{2}\b")

SQL_OBJECT = re.compile(
    r"^\s*CREATE\s+(?:OR\s+REPLACE\s+)?(TABLE|VIEW|TYPE|FUNCTION|MATERIALIZED\s+VIEW)"
    r"\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z_][A-Za-z0-9_]*)",
    re.IGNORECASE,
)

# Four to sixteen spaces: these suites use @Nested classes, so a test method sits at eight or
# twelve. Anchoring on four alone attributed almost every citation to the class instead of the
# method, and the matrix filled with "<class-level citation>" — a coarser answer that still looked
# like an answer.
JAVA_METHOD = re.compile(
    r"^ {4,16}(?:(?:public|private|protected|static|final)\s+)*void\s+(\w+)\s*\(")

# Verification is a pipe-separated SET in the register: AUTOMATED_TEST|PENETRATION_TEST, and so on.
# A requirement owes an automated test if and only if AUTOMATED_TEST is one of its methods. Treating
# the field as a single value matched only the 719 plain AUTOMATED_TEST rows and read every combined
# value as unrecognised, so requirements verified by test AND penetration test were scored as owing
# nothing.
NON_AUTOMATED_LABEL = {
    "DESIGN_INSPECTION": "design inspection",
    "DOCUMENT_INSPECTION": "document inspection",
    "DEMONSTRATION": "demonstration — a rehearsal or walkthrough, not a test",
    "CODE_REVIEW": "code review; the artifact is the review record",
    "ARCHITECTURE_REVIEW": "architecture review; the artifact is the review record",
    "CONDITIONAL_REVIEW": "conditional review",
    "PENETRATION_TEST": "penetration test, scheduled before commercial release",
    "MANUAL_TEST": "manual test",
}


def methods_of(verification):
    return {m.strip() for m in verification.split("|") if m.strip()}


def owes_automated_test(verification):
    return "AUTOMATED_TEST" in methods_of(verification)


def non_automated_reason(verification):
    methods = methods_of(verification)
    if not methods:
        return "the register records no verification method for this requirement — a register gap"
    return "verified by " + ", ".join(
        NON_AUTOMATED_LABEL.get(m, m.lower().replace("_", " ")) for m in sorted(methods))


def walk_sources():
    """Every source file under src/, excluding build output and the Gradle cache."""
    for dirpath, dirnames, filenames in os.walk(SRC):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle", "bin")]
        for name in filenames:
            if name.endswith((".java", ".sql", ".kts")):
                yield os.path.join(dirpath, name)


def relative(path):
    return os.path.relpath(path, ROOT)


def java_methods(lines):
    """Line ranges per method, with whether the method is @Disabled.

    Returns a list of (start, end, name, disabled). The annotation block above a method is
    included in its range, so an identifier cited in a @DisplayName counts for that method.
    """
    starts = []
    for index, line in enumerate(lines):
        match = JAVA_METHOD.match(line)
        if match:
            block_start = index
            while block_start > 0 and lines[block_start - 1].strip().startswith(("@", "*", "/*", "//")):
                block_start -= 1
            starts.append((block_start, index, match.group(1)))

    methods = []
    for position, (block_start, decl, name) in enumerate(starts):
        end = starts[position + 1][0] if position + 1 < len(starts) else len(lines)
        annotations = "\n".join(lines[block_start:decl + 1])
        methods.append((block_start, end, name, "@Disabled" in annotations))
    return methods


def sql_objects(lines):
    """Line ranges per schema object, and the object's kind and name."""
    starts = []
    for index, line in enumerate(lines):
        match = SQL_OBJECT.match(line)
        if match:
            block_start = index
            # A leading comment block belongs to the object it introduces. Blank lines between the
            # banner and the CREATE are conventional in these migrations, so they are stepped over --
            # not doing so attributed nothing to org_node, whose banner sits two lines above it.
            while block_start > 0 and (
                    lines[block_start - 1].lstrip().startswith("--")
                    or (not lines[block_start - 1].strip()
                        and block_start > 1 and lines[block_start - 2].lstrip().startswith("--"))):
                block_start -= 1
            starts.append((block_start, index, match.group(1).upper().replace("  ", " "), match.group(2)))

    objects = []
    for position, (block_start, _decl, kind, name) in enumerate(starts):
        end = starts[position + 1][0] if position + 1 < len(starts) else len(lines)
        objects.append((block_start, end, kind, name))
    return objects


def test_class_results():
    """Failure and skip counts per fully-qualified test class, from the JUnit XML."""
    results = {}
    patterns = [
        os.path.join(SRC, "*", "build", "test-results", "test", "*.xml"),
        os.path.join(SRC, "*", "*", "build", "test-results", "test", "*.xml"),
    ]
    for pattern in patterns:
        for path in glob.glob(pattern):
            root = ET.parse(path).getroot()
            for case in root.iter("testcase"):
                classname = case.get("classname", "")
                outer = classname.split("$")[0]
                entry = results.setdefault(outer, {"tests": 0, "failed": 0, "skipped": 0})
                entry["tests"] += 1
                if case.find("failure") is not None or case.find("error") is not None:
                    entry["failed"] += 1
                if case.find("skipped") is not None:
                    entry["skipped"] += 1
    return results


MANIFEST = os.path.join(ROOT, "_traceability", "api-operations.csv")


def registered_operations():
    """Every API operation, read from the manifest the build writes.

    Previously this parsed PlatformOperations.java for constructor calls. That worked while operations
    were listed one by one and SILENTLY became wrong the moment they were derived from a catalogue:
    the parser found four calls inside a loop and reported four operations where the platform serves
    twenty-three. A parser guesses at what code will do; :app's OperationManifestTest runs it.

    An absent manifest means the build has not run, which is reported as unknown rather than as zero.
    Zero would read as "no operations" and pass the backward gate vacuously — the failure this whole
    column exists to avoid.
    """
    if not os.path.exists(MANIFEST):
        return None
    with open(MANIFEST, encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    return [(f"{r['method']} {r['path']}", r["traces_to"].split()) for r in rows]


def collect():
    design = defaultdict(list)
    schema = defaultdict(list)
    api = defaultdict(list)
    tests = defaultdict(list)          # requirement -> [(fqcn, method, disabled)]
    schema_objects = []                # (file, kind, name, [requirement ids])
    cited_anywhere = set()

    for path in walk_sources():
        with open(path, encoding="utf-8") as handle:
            lines = handle.read().split("\n")
        text = "\n".join(lines)
        found = set(ID_PATTERN.findall(text))
        # A .sql file always goes through the full pass. Short-circuiting on "no requirement
        # identifier in the file" recorded every object in V007 as untraced, because that migration
        # cites invariants and design sections and no requirement identifier at all — the shortcut
        # threw away the two traces that were there. Found by spot-checking a name on the fail list
        # against the file, which is the check a summary count does not invite.
        if not found and not path.endswith(".sql"):
            continue
        cited_anywhere |= found
        rel = relative(path)

        if path.endswith(".sql"):
            for start, end, kind, name in sql_objects(lines):
                block = "\n".join(lines[start:end])
                block_ids = sorted(set(ID_PATTERN.findall(block)))
                sections = sorted(set(DOC_SECTION.findall(block))) \
                    + sorted(set(INVARIANT.findall(block)))
                # Whether the block explains itself in prose. An object with a rationale and no
                # identifier is a CITATION gap: the trace exists in the author's head and in the
                # comment, and not in anything a tool or a descoping decision can follow. An object
                # with neither is the undocumented functionality PRD-PLT-012 is actually about.
                has_prose = sum(1 for line in lines[start:end] if line.lstrip().startswith("--")) >= 3
                schema_objects.append((rel, kind, name, block_ids, sections, has_prose))
                for requirement in block_ids:
                    schema[requirement].append(f"{name} ({kind.lower()}, {os.path.basename(rel)})")
            # Identifiers cited in a file header, outside any object block.
            for requirement in found:
                if requirement not in schema:
                    schema[requirement].append(os.path.basename(rel))
            continue

        if not path.endswith(".java"):
            for requirement in found:
                design[requirement].append(rel)
            continue

        is_test = f"{os.sep}src{os.sep}test{os.sep}" in path
        package_match = re.search(r"^package\s+([\w.]+);", text, re.MULTILINE)
        package = package_match.group(1) if package_match else ""
        outer_class = os.path.basename(path)[:-len(".java")]
        fqcn = f"{package}.{outer_class}" if package else outer_class

        if is_test:
            methods = java_methods(lines)
            for start, end, name, disabled in methods:
                block = "\n".join(lines[start:end])
                for requirement in set(ID_PATTERN.findall(block)):
                    tests[requirement].append((fqcn, name, disabled))
            # Class-level citations, outside any method.
            covered = set()
            for start, end, _name, _disabled in methods:
                covered |= set(range(start, end))
            header = "\n".join(line for index, line in enumerate(lines) if index not in covered)
            for requirement in set(ID_PATTERN.findall(header)):
                tests[requirement].append((fqcn, "<class-level citation>", False))
        else:
            bucket = api if f"{os.sep}app{os.sep}src{os.sep}main{os.sep}java{os.sep}aspm{os.sep}app{os.sep}api{os.sep}" in path else design
            for requirement in found:
                bucket[requirement].append(f"{outer_class}")

    return design, schema, api, tests, schema_objects, cited_anywhere


def cell(values, artifact, verification, status):
    """A populated cell, an N/A with a reason, or a MISSING with a reason.

    The temptation here is an N/A reason that sounds authoritative -- "the requirement constrains
    behaviour, not storage" -- for every empty cell. It reads well and it is a guess: this tool
    cannot tell whether a requirement needs a table, and asserting it does not is how a matrix comes
    to certify absences nobody checked. So N/A is reserved for the two cases that are knowable from
    the register alone, and everything else says MISSING and names what is missing.
    """
    if values:
        unique = sorted(set(values))
        shown = "; ".join(unique[:3])
        if len(unique) > 3:
            shown += f"; +{len(unique) - 3} more"
        return shown
    if status != "Active":
        return f"N/A (status {status}; a superseded requirement owes no artifact)"
    if not owes_automated_test(verification):
        return f"N/A ({non_automated_reason(verification)}; no code artifact is owed)"
    return f"MISSING (no {artifact} cites this requirement)"


def main():
    with open(REGISTER, encoding="utf-8", newline="") as handle:
        requirements = list(csv.DictReader(handle))

    design, schema, api, tests, schema_objects, _cited = collect()
    class_results = test_class_results()

    rows = []
    forward_gaps = []
    disabled_only = []
    implemented_untested = []

    for requirement in requirements:
        rid = requirement["id"]
        owning = requirement["owning_doc"]
        priority = requirement["priority"]
        verification = requirement["verification"]
        status = requirement["status"]

        citations = tests.get(rid, [])
        passing = []
        disabled = []
        for fqcn, method, is_disabled in citations:
            result = class_results.get(fqcn)
            if is_disabled:
                disabled.append(f"{fqcn.split('.')[-1]}#{method} (@Disabled)")
            elif result and result["failed"] == 0:
                passing.append(f"{fqcn.split('.')[-1]}#{method}")
            elif result:
                disabled.append(f"{fqcn.split('.')[-1]}#{method} (FAILING)")
            else:
                disabled.append(f"{fqcn.split('.')[-1]}#{method} (not executed)")

        if passing:
            test_cell = "; ".join(sorted(set(passing))[:3])
            if len(set(passing)) > 3:
                test_cell += f"; +{len(set(passing)) - 3} more"
        elif disabled:
            test_cell = "MISSING (cited only by " + "; ".join(sorted(set(disabled))[:2]) + ")"
            if priority == "MUST_HAVE" and status == "Active":
                disabled_only.append((rid, owning, sorted(set(disabled))))
        elif not owes_automated_test(verification):
            test_cell = f"N/A ({non_automated_reason(verification)}; no automated test is owed)"
        elif status != "Active":
            test_cell = f"N/A (status {status}; a superseded requirement owes no test)"
        else:
            test_cell = "MISSING (no test cites this requirement)"
            if priority == "MUST_HAVE":
                forward_gaps.append((rid, owning, verification))
                # Two very different gaps wear the same label. A requirement no code cites is not
                # built; a requirement main code cites and no test cites IS built and unverified,
                # which is the worse of the two and the one a headline percentage hides.
                if design.get(rid) or schema.get(rid) or api.get(rid):
                    implemented_untested.append((rid, owning))

        rows.append({
            "requirement_id": rid,
            "owning_doc": owning,
            "design_ref": cell(design.get(rid), "main source file", verification, status),
            "schema_ref": cell(schema.get(rid), "schema object", verification, status),
            "api_ref": cell(api.get(rid), "API operation", verification, status),
            "test_case_id": test_cell,
            "verification_method": verification,
            "status": status,
            "notes": f"priority {priority}",
        })

    # ---- backward traceability -------------------------------------------------------
    # Three outcomes, not two. An object citing DOC-04 section 11.2.2 or INV-WRK-08, and no
    # requirement identifier, traces to the DESIGN or to the domain model rather than to a
    # requirement: weaker than the gate asks for, stronger than nothing, and collapsing it into
    # either direction misreports the state of the schema. PRD-PLT-012 asks for objects "tracing to
    # no requirement", so only the third list fails the gate.
    traced_to_requirement = [o for o in schema_objects if o[3]]
    traced_to_section_only = [o for o in schema_objects if not o[3] and o[4]]
    untraced_schema = [(o[0], o[1], o[2], o[5]) for o in schema_objects if not o[3] and not o[4]]

    # Partitions are created by the ensure_*_partitions functions and by the DO blocks that
    # provision hash partitions; they inherit their parent's tracing and are not separate
    # objects. Nothing here creates one with a literal CREATE TABLE, so no filter is needed --
    # recorded so a later reader does not add one and hide a real untraced table.

    header_notes = [
        "# Requirement-to-test traceability matrix. Owned by DOC-16 section 16; format per DOC-00 section 8.3.",
        "# Generated by tools/generate_matrix.py from _traceability/requirements.csv and the source tree.",
        "#",
        "# Three cell values, because a blank and a gap are different things:",
        "#   <value>          the artifact, cited by requirement identifier in the source",
        "#   N/A (reason)     the requirement owes no artifact: superseded, or verified by a",
        "#                    method that produces no code (design inspection, demonstration,\n#                    code review, architecture review, penetration test)\n#   MISSING (reason) no artifact of that kind cites the requirement -- this is a GAP.\n#                    Some requirements legitimately need no table and no endpoint, which is\n#                    why only the test_case_id column feeds a release gate. This tool cannot\n#                    tell the two apart, and says MISSING rather than guessing N/A.",
        "#",
        "# Evidence is a requirement identifier cited in source. That means somebody wrote the",
        "# identifier next to the code; it is coverage, not conformance.",
        "#",
        "# 'Passing' is per test CLASS, from the JUnit XML: Gradle writes @DisplayName into the",
        "# name attribute, so the method name is absent from the results. A @Disabled method is",
        "# detected in source and never counted as passing.",
        "#",
        "# Release gates: see _traceability/GAPS.md.",
    ]

    with open(MATRIX, "w", encoding="utf-8", newline="") as handle:
        for note in header_notes:
            handle.write(note + "\n")
        writer = csv.DictWriter(handle, fieldnames=[
            "requirement_id", "owning_doc", "design_ref", "schema_ref", "api_ref",
            "test_case_id", "verification_method", "status", "notes"])
        writer.writeheader()
        writer.writerows(rows)

    # ---- the gap report --------------------------------------------------------------
    must_have_active = [r for r in requirements if r["priority"] == "MUST_HAVE" and r["status"] == "Active"]
    covered = len(must_have_active) - len(forward_gaps) - len(disabled_only)

    by_doc = defaultdict(list)
    for rid, owning, verification in forward_gaps:
        by_doc[owning].append((rid, verification))

    with open(GAPS, "w", encoding="utf-8") as handle:
        handle.write("# Release gates — DOC-16 section 16.2\n\n")
        handle.write("Generated by `tools/generate_matrix.py`. Do not edit by hand.\n\n")
        handle.write("A gap is closed by writing the test, never by weakening the requirement.\n\n")

        handle.write("## Forward: every MUST_HAVE requirement has a passing test case\n\n")
        handle.write(f"**FAIL** — {covered} of {len(must_have_active)} active MUST_HAVE requirements "
                     f"({100.0 * covered / len(must_have_active):.1f}%) are cited by a passing test.\n\n"
                     if forward_gaps or disabled_only else
                     f"**PASS** — all {len(must_have_active)} active MUST_HAVE requirements are cited "
                     "by a passing test.\n\n")
        handle.write(f"- {len(forward_gaps)} cited by no test at all, of which "
                     f"**{len(implemented_untested)} are cited by main source or schema** — built "
                     "and unverified, which is the worse of the two gaps and the one a headline "
                     f"percentage hides. The other {len(forward_gaps) - len(implemented_untested)} "
                     "are not built.\n")
        handle.write(f"- {len(disabled_only)} cited only by a `@Disabled`, failing, or unexecuted test\n\n")

        if disabled_only:
            handle.write("### Cited only by a disabled or failing test\n\n")
            handle.write("| Requirement | Document | Citation |\n|---|---|---|\n")
            for rid, owning, citations in sorted(disabled_only):
                handle.write(f"| `{rid}` | {owning} | {'; '.join(citations[:2])} |\n")
            handle.write("\n")

        if implemented_untested:
            handle.write("### Built and unverified — cited by code, by no test\n\n")
            handle.write("| Requirement | Document |\n|---|---|\n")
            for rid, owning in sorted(implemented_untested):
                handle.write(f"| `{rid}` | {owning} |\n")
            handle.write("\n")

        handle.write("### Cited by no test, by owning document\n\n")
        handle.write("| Document | MUST_HAVE without a test |\n|---|---|\n")
        for owning in sorted(by_doc):
            handle.write(f"| {owning} | {len(by_doc[owning])} |\n")
        handle.write("\n<details><summary>Every identifier</summary>\n\n")
        for owning in sorted(by_doc):
            handle.write(f"**{owning}** — "
                         + ", ".join(f"`{rid}`" for rid, _v in sorted(by_doc[owning])) + "\n\n")
        handle.write("</details>\n\n")

        no_method = [r["id"] for r in requirements
                     if not methods_of(r["verification"]) and r["status"] == "Active"]
        if no_method:
            handle.write("### Register gap — requirements with no verification method\n\n")
            handle.write(f"{len(no_method)} active requirement(s) record no verification method at "
                         "all: " + ", ".join(f"`{rid}`" for rid in sorted(no_method)) + ". A "
                         "requirement with no method cannot be shown to be met or shown to be "
                         "missed, and it is invisible to both gates — it is neither owed a test nor "
                         "counted as lacking one. This is a defect in the register or in the owning "
                         "document, not in the implementation.\n\n")

        handle.write("## Backward: zero schema objects or API operations tracing to no requirement\n\n")
        handle.write(f"{len(schema_objects)} schema objects across the thirteen migrations:\n\n")
        handle.write(f"- {len(traced_to_requirement)} cite a requirement identifier in their own "
                     "definition block\n")
        handle.write(f"- {len(traced_to_section_only)} cite a DOC section but no requirement "
                     "identifier — traced to the design or to an invariant, not to a "
                     "requirement\n")
        handle.write(f"- {len(untraced_schema)} cite neither\n\n")

        if untraced_schema:
            handle.write(f"**FAIL** — {len(untraced_schema)} schema object(s) trace to nothing.\n\n")
            documented = sum(1 for o in untraced_schema if o[3])
            handle.write(f"{documented} of them explain themselves in prose and name no traceable "
                         "identifier: the trace exists in the comment and in the author's head, and "
                         "not in anything a tool or a descoping decision can follow. That is a "
                         "citation gap. The remaining "
                         f"{len(untraced_schema) - documented} carry neither, which is the "
                         "undocumented functionality PRD-PLT-012 is about — 'attack surface and "
                         "test burden nobody agreed to accept'.\n\n")
            handle.write("| Object | Kind | Migration | Has a prose rationale |\n|---|---|---|---|\n")
            for path, kind, name, prose in sorted(untraced_schema, key=lambda o: (o[0], o[2])):
                handle.write(f"| `{name}` | {kind.lower()} | `{os.path.basename(path)}` | "
                             f"{'yes' if prose else 'NO'} |\n")
            handle.write("\nClosing these means adding the requirement identifier the object "
                         "already implements, in the migration that defines it. It is not closed "
                         "here: a citation added to satisfy a gate on the day the gate was written "
                         "is the weakest evidence available, and it belongs to whoever can confirm "
                         "the mapping against the owning document.\n\n")
        else:
            handle.write("**PASS** — every schema object traces to a requirement or to the design "
                         "section that specifies it.\n\n")

        if traced_to_section_only:
            handle.write("<details><summary>Traced to a design section or an invariant but not to "
                         "a requirement "
                         f"({len(traced_to_section_only)})</summary>\n\n")
            handle.write("These are not gate failures. They are the weaker trace: a reader can find "
                         "the design that specifies the object but not the requirement that obliges "
                         "it, so a descoping decision cannot see what the object is for.\n\n")
            handle.write("| Object | Kind | Traced to |\n|---|---|---|\n")
            for path, kind, name, _ids, sections, _prose in sorted(traced_to_section_only,
                                                                    key=lambda o: (o[0], o[2])):
                handle.write(f"| `{name}` | {kind.lower()} | {sections[0]} |\n")
            handle.write("\n</details>\n\n")

        operations = registered_operations()
        known_ids = {r["id"] for r in requirements}
        if operations is not None:
            fabricated = sorted({i for _n, ids in operations for i in ids if i not in known_ids})
            if fabricated:
                handle.write("**FAIL (API citations)** — these operations cite requirement identifiers "
                             "that do not exist in the register: "
                             + ", ".join(f"`{i}`" for i in fabricated)
                             + ". A citation naming nothing is worse than none: it reads as traced, and "
                             "no compiler catches it.\n\n")
        if operations is None:
            handle.write("**UNKNOWN (API)** — `_traceability/api-operations.csv` is absent, so the "
                         "registry has not been enumerated. The build writes it; run `verify.sh`. "
                         "Reported as unknown rather than as zero, because zero would read as 'no "
                         "operations' and pass this gate vacuously.\n")
        elif not operations:
            handle.write("**VACUOUS (API)** — the operation registry holds no operations, so backward "
                         "traceability over API operations has nothing to check. Recorded as vacuous "
                         "rather than as a pass: a gate over an empty set passes for the wrong reason, "
                         "and that is exactly how a gate stops being a gate.\n")
        else:
            untraced_operations = [name for name, ids in operations if not ids]
            handle.write(f"{len(operations)} operation(s) registered in `PlatformOperations`.\n\n")
            if untraced_operations:
                handle.write(f"**FAIL** — {len(untraced_operations)} cite no requirement: "
                             + ", ".join(f"`{name}`" for name in untraced_operations) + "\n\n")
            else:
                handle.write("**PASS** — every registered operation cites a requirement in its "
                             "block.\n\n")
            handle.write("| Operation | Traced to |\n|---|---|\n")
            for name, ids in operations:
                handle.write(f"| `{name}` | "
                             + (", ".join(f"`{i}`" for i in ids) if ids else "**nothing**") + " |\n")
            handle.write("\n**The gate passing is not the API being built.** DOC-05 specifies the API "
                         "for every resource group; this is one group. Backward traceability asks "
                         "whether what exists traces to a requirement, and forward coverage — the "
                         "other gate above — is where the missing operations show up.\n")

    print(f"matrix     : {len(rows)} requirements -> {relative(MATRIX)}")
    print(f"schema     : {len(schema_objects)} objects — {len(traced_to_requirement)} to a "
          f"requirement, {len(traced_to_section_only)} to a section only, {len(untraced_schema)} "
          "to nothing")
    print(f"forward    : {covered}/{len(must_have_active)} active MUST_HAVE cited by a passing test")
    print(f"             {len(forward_gaps)} no test ({len(implemented_untested)} of them built "
          f"and unverified), {len(disabled_only)} disabled/failing only")
    ops = registered_operations()
    if ops is None:
        print("api        : manifest absent — run verify.sh; reported as unknown, never as zero")
    else:
        known = {r["id"] for r in csv.DictReader(open(REGISTER, encoding="utf-8", newline=""))}
        bad = sorted({i for _n, ids in ops for i in ids if i not in known})
        print(f"api        : {len(ops)} operation(s) registered, "
              f"{sum(1 for _n, ids in ops if not ids)} citing no requirement"
              + (f", {len(bad)} FABRICATED citation(s): {bad}" if bad else ""))
    print(f"gaps       : {relative(GAPS)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
