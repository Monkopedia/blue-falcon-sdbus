#!/usr/bin/env bash
#
# check-release-docs.sh — verify the release-scoped claims in README.md (and
# RELEASING.md) against the POM of the release they name and against the repo.
#
# README.md carries three claims that are scoped to one specific release:
#
#   1. the Install snippet's coordinate  (com.monkopedia:blue-falcon-sdbus:X)
#   2. the sentence "This table describes **X**, the release the Install
#      snippet above pins."
#   3. the Compatibility table's rows, which are the dependency versions that
#      shipped in X.
#
# The authority for (3) is the POM that was published for X — not
# gradle/libs.versions.toml, which describes `main` rather than a release, and
# which records *declared* versions rather than the versions that were actually
# resolved and published.
#
# ⚠️ SUBSTITUTION, AND ITS EVIDENCE IS A SAMPLE OF ONE.
# At tag time the release being cut is NOT published yet, so fetching the POM
# for the version the README names would 404 on every release. release.yml
# therefore passes --pom-file pointing at the POM Gradle is about to UPLOAD
# (generatePomFileForJvmPublication), not one fetched from Central. Fetching the
# published POM remains this script's default when --pom-file is omitted.
#
# That substitution was verified ONCE: for 1.2.3-3.4.1 the generated and
# published POMs are byte-identical (sha256 2609e372…, 2594 B each), measured
# 2026-08-23. n=1. If POM generation ever diverges — a Gradle or plugin bump,
# added metadata, a renamed publication — this check starts validating a
# document that is not what ships, AND IT WILL STILL PASS. Nothing here detects
# that. The cheapest way to convert n=1 into a per-release measurement is a
# post-publish curl+diff of the POM that was just uploaded; it cannot gate the
# release it measures, but it would catch divergence before the next one.
#
# This script is invoked by .github/workflows/release.yml, before anything is
# published, so a wrong README fails the release instead of shipping with it.
# It is also runnable by hand against an already-published release:
#
#   .github/scripts/check-release-docs.sh                     # vs Maven Central
#   .github/scripts/check-release-docs.sh --pom-file some.pom # vs a local POM
#
# It also checks one non-POM literal that drifts by the same mechanism: the
# integration-test count quoted in the docs, against the number of @Test
# functions in :integration-tests.
#
# Exit codes — deliberately distinct, because "could not compare" must never
# look like "compared and found nothing wrong":
#
#   0  every row was compared against a real POM and agreed
#   1  the README disagrees with the release (a real mismatch)
#   2  the POM could not be retrieved, could not be parsed, or is not the POM
#      for the release the README names — nothing was compared
#   3  the README (or gradle.properties) could not be parsed — nothing was
#      compared
#
set -uo pipefail

GROUP_ID="com.monkopedia"
ARTIFACT_ID="blue-falcon-sdbus-jvm"

README="README.md"
GRADLE_PROPERTIES="gradle.properties"
WRAPPER_PROPERTIES="gradle/wrapper/gradle-wrapper.properties"
TESTS_DIR="integration-tests/src"
COUNT_DOCS=()
POM_FILE=""
POM_URL=""
EXPECT_VERSION=""

usage() {
    sed -n '2,40p' "$0"
    exit 64
}

while [ $# -gt 0 ]; do
    case "$1" in
        --readme)              README="$2"; shift 2 ;;
        --gradle-properties)   GRADLE_PROPERTIES="$2"; shift 2 ;;
        --wrapper-properties)  WRAPPER_PROPERTIES="$2"; shift 2 ;;
        --tests-dir)           TESTS_DIR="$2"; shift 2 ;;
        --count-doc)           COUNT_DOCS+=("$2"); shift 2 ;;
        --pom-file)            POM_FILE="$2"; shift 2 ;;
        --pom-url)             POM_URL="$2"; shift 2 ;;
        --expect-version)      EXPECT_VERSION="$2"; shift 2 ;;
        -h|--help)             usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done

[ "${#COUNT_DOCS[@]}" -gt 0 ] || COUNT_DOCS=("$README" "RELEASING.md")

# ---------------------------------------------------------------------------
# Reporting helpers. Every failure prints a ::error:: annotation *and* a plain
# line, so the failure is visible in the Actions UI and in raw logs.
# ---------------------------------------------------------------------------

REPORT=""
say() {
    echo "$1"
    REPORT="${REPORT}${1}"$'\n'
}

fail() {
    # fail <exit-code> <message...>
    local code="$1"; shift
    local msg="$*"
    echo "::error::$msg"
    echo "CHECK FAILED ($code): $msg" >&2
    emit_summary
    exit "$code"
}

emit_summary() {
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        {
            echo "### Release docs version check"
            echo
            echo '```'
            printf '%s' "$REPORT"
            echo '```'
        } >> "$GITHUB_STEP_SUMMARY"
    fi
}

trim() {
    local s="$1"
    s="${s#"${s%%[![:space:]]*}"}"
    s="${s%"${s##*[![:space:]]}"}"
    printf '%s' "$s"
}

# ---------------------------------------------------------------------------
# 1. Parse the local files. Anything unparsable is exit 3: we have nothing to
#    compare, which must not be reported as agreement.
# ---------------------------------------------------------------------------

[ -f "$README" ]            || fail 3 "README not found at '$README'"
[ -s "$README" ]            || fail 3 "README at '$README' is empty"
[ -f "$GRADLE_PROPERTIES" ] || fail 3 "gradle.properties not found at '$GRADLE_PROPERTIES'"

PROJECT_VERSION=$(grep -E '^version=' "$GRADLE_PROPERTIES" | head -1 | cut -d= -f2-)
PROJECT_VERSION=$(trim "$PROJECT_VERSION")
[ -n "$PROJECT_VERSION" ] || fail 3 "no 'version=' line in '$GRADLE_PROPERTIES'"

# --- Install coordinate ---
mapfile -t COORD_VERSIONS < <(
    grep -oE "${GROUP_ID}:blue-falcon-sdbus:[0-9A-Za-z._+-]+" "$README" | sed 's/.*://'
)
COORD_COUNT=${#COORD_VERSIONS[@]}
[ "$COORD_COUNT" -ge 1 ] || fail 3 \
    "no '${GROUP_ID}:blue-falcon-sdbus:<version>' coordinate found in '$README' — the check has nothing to verify"
INSTALL_VERSION="${COORD_VERSIONS[0]}"
for v in "${COORD_VERSIONS[@]}"; do
    [ "$v" = "$INSTALL_VERSION" ] || fail 1 \
        "'$README' pins more than one version of ${GROUP_ID}:blue-falcon-sdbus ('$INSTALL_VERSION' and '$v')"
done

# --- "This table describes **X**" ---
mapfile -t NAMED_MATCHES < <(
    grep -oE 'This table describes \*\*[^*]+\*\*' "$README" | sed -E 's/.*\*\*(.+)\*\*/\1/'
)
NAMED_COUNT=${#NAMED_MATCHES[@]}
[ "$NAMED_COUNT" -eq 1 ] || fail 3 \
    "expected exactly 1 'This table describes **<version>**' sentence in '$README', found $NAMED_COUNT — the check cannot tell which release the table describes"
NAMED_VERSION="${NAMED_MATCHES[0]}"

# --- Compatibility table ---
declare -A TABLE=()
TABLE_ROWS=0
while IFS= read -r line; do
    case "$line" in \|*) ;; *) continue ;; esac
    name=$(trim "$(printf '%s' "$line" | awk -F'|' '{print $2}')")
    version=$(trim "$(printf '%s' "$line" | awk -F'|' '{print $3}')")
    # Skip the header row and the |---|---| separator.
    [ -z "$name" ] && continue
    [ "$version" = "Version" ] && continue
    case "$name" in *[!-\ ]*) ;; *) continue ;; esac
    [ -n "$version" ] || fail 3 "compatibility table row '$name' has no version"
    TABLE["$name"]="$version"
    TABLE_ROWS=$((TABLE_ROWS + 1))
done < <(awk '/^## Compatibility/{f=1;next} f && /^## /{exit} f' "$README")

[ "$TABLE_ROWS" -ge 5 ] || fail 3 \
    "parsed only $TABLE_ROWS rows from the compatibility table in '$README' (expected at least 5) — a grep over an empty table reports clean, so this is a failure"

# ---------------------------------------------------------------------------
# 2. The three README-local claims must agree with each other and with the
#    version being released. This is what makes the table's authority - the
#    published POM - the POM of *this* release.
# ---------------------------------------------------------------------------

[ -n "$EXPECT_VERSION" ] || EXPECT_VERSION="$PROJECT_VERSION"

if [ "$INSTALL_VERSION" != "$NAMED_VERSION" ]; then
    fail 1 "README is self-inconsistent: the Install snippet pins '$INSTALL_VERSION' but the compatibility table says it describes '$NAMED_VERSION'"
fi
if [ "$NAMED_VERSION" != "$EXPECT_VERSION" ]; then
    fail 1 "README describes release '$NAMED_VERSION' but the release being cut is '$EXPECT_VERSION' — update the Install snippet, the 'This table describes' sentence and the compatibility table before tagging"
fi

# ---------------------------------------------------------------------------
# 3. Obtain the POM. Retrieval failure is exit 2 and must never fall through
#    into the comparison, which would find no mismatch for want of anything to
#    compare.
# ---------------------------------------------------------------------------

POM_SOURCE=""
POM_PATH=""
CLEANUP=""

if [ -n "$POM_FILE" ] && [ -n "$POM_URL" ]; then
    echo "--pom-file and --pom-url are mutually exclusive" >&2
    exit 64
fi

if [ -n "$POM_FILE" ]; then
    POM_SOURCE="file://$POM_FILE"
    POM_PATH="$POM_FILE"
    [ -f "$POM_PATH" ] || fail 2 "Could not retrieve the POM: no file at '$POM_PATH'"
    [ -s "$POM_PATH" ] || fail 2 "Could not retrieve the POM: '$POM_PATH' is empty"
else
    if [ -z "$POM_URL" ]; then
        POM_URL="https://repo1.maven.org/maven2/${GROUP_ID//.//}/${ARTIFACT_ID}/${EXPECT_VERSION}/${ARTIFACT_ID}-${EXPECT_VERSION}.pom"
    fi
    POM_SOURCE="$POM_URL"
    POM_PATH="$(mktemp)"
    CLEANUP="$POM_PATH"
    # -L: without it a 301 yields an HTML body that parses as "no dependencies".
    HTTP_CODE=$(curl -sSL --retry 3 --retry-delay 2 --max-time 60 \
        -o "$POM_PATH" -w '%{http_code}' "$POM_URL" 2>/dev/null)
    CURL_RC=$?
    if [ "$CURL_RC" -ne 0 ]; then
        fail 2 "Could not retrieve the POM: curl exited $CURL_RC for $POM_URL"
    fi
    if [ "$HTTP_CODE" != "200" ]; then
        fail 2 "Could not retrieve the POM: HTTP $HTTP_CODE for $POM_URL (a 404 means the coordinate was not answered, not that the README is current)"
    fi
    [ -s "$POM_PATH" ] || fail 2 "Could not retrieve the POM: HTTP 200 but an empty body from $POM_URL"
fi

POM_BYTES=$(wc -c < "$POM_PATH" | tr -d ' ')
[ "$POM_BYTES" -gt 0 ] || fail 2 "Could not retrieve the POM: 0 bytes from $POM_SOURCE"

# ---------------------------------------------------------------------------
# 4. Parse the POM. A retrieved-but-unparsable document is also exit 2.
# ---------------------------------------------------------------------------

POM_DUMP=$(python3 - "$POM_PATH" <<'PY'
import sys, xml.etree.ElementTree as ET

path = sys.argv[1]
try:
    root = ET.parse(path).getroot()
except Exception as exc:
    print("parse_error=%s" % exc)
    sys.exit(9)

ns = root.tag.split('}')[0] + '}' if root.tag.startswith('{') else ''

def text(el, name):
    child = el.find(ns + name)
    return child.text.strip() if child is not None and child.text else ''

print("groupId=" + text(root, 'groupId'))
print("artifactId=" + text(root, 'artifactId'))
print("version=" + text(root, 'version'))

deps = root.find(ns + 'dependencies')
count = 0
if deps is not None:
    for dep in deps.findall(ns + 'dependency'):
        g, a, v = text(dep, 'groupId'), text(dep, 'artifactId'), text(dep, 'version')
        if g and a and v:
            count += 1
            print("dep=%s:%s:%s" % (g, a, v))
print("depcount=%d" % count)
PY
)
PY_RC=$?
[ -n "$CLEANUP" ] && rm -f "$CLEANUP"

if [ "$PY_RC" -ne 0 ]; then
    fail 2 "Retrieved $POM_BYTES bytes from $POM_SOURCE but could not parse it as a POM ($(printf '%s' "$POM_DUMP" | head -1))"
fi

declare -A POM_DEPS=()
POM_GROUP=""; POM_ARTIFACT=""; POM_VERSION=""; POM_DEPCOUNT=0
while IFS= read -r line; do
    case "$line" in
        groupId=*)    POM_GROUP="${line#groupId=}" ;;
        artifactId=*) POM_ARTIFACT="${line#artifactId=}" ;;
        version=*)    POM_VERSION="${line#version=}" ;;
        depcount=*)   POM_DEPCOUNT="${line#depcount=}" ;;
        dep=*)
            spec="${line#dep=}"
            POM_DEPS["${spec%:*}"]="${spec##*:}"
            ;;
    esac
done <<< "$POM_DUMP"

# The retrieved document must actually be the POM for the release the README
# names, and must actually declare dependencies. Otherwise there is nothing to
# compare and the run must not be green.
[ "$POM_ARTIFACT" = "$ARTIFACT_ID" ] || fail 2 \
    "Retrieved a document from $POM_SOURCE whose artifactId is '$POM_ARTIFACT', not '$ARTIFACT_ID' — nothing was compared"
[ "$POM_VERSION" = "$EXPECT_VERSION" ] || fail 2 \
    "Retrieved the POM for version '$POM_VERSION' from $POM_SOURCE, but the README describes '$EXPECT_VERSION' — nothing was compared"
[ "$POM_DEPCOUNT" -ge 1 ] || fail 2 \
    "The POM at $POM_SOURCE declares 0 dependencies — a comparison against it would be vacuous"

# ---------------------------------------------------------------------------
# 5. Compare. Every table row must be verified against something; a row the
#    check does not recognise is a failure, not a row to skip silently.
# ---------------------------------------------------------------------------

# Table row label -> the base Maven coordinate that carries its version in the
# POM. The published jvm POM uses -jvm-suffixed artifactIds for multiplatform
# dependencies, so both spellings are accepted.
declare -A ROW_DEP=(
    ["Kotlin"]="org.jetbrains.kotlin:kotlin-stdlib"
    ["blue-falcon-core"]="dev.bluefalcon:blue-falcon-core"
    ["sdbus-kotlin"]="com.monkopedia:sdbus-kotlin"
    ["kotlinx-coroutines"]="org.jetbrains.kotlinx:kotlinx-coroutines-core"
    ["kotlinx-serialization"]="org.jetbrains.kotlinx:kotlinx-serialization-core"
)

WRAPPER_VERSION=""
if [ -f "$WRAPPER_PROPERTIES" ]; then
    WRAPPER_VERSION=$(grep -oE 'gradle-[0-9][0-9A-Za-z.-]*-(bin|all)\.zip' "$WRAPPER_PROPERTIES" |
        head -1 | sed -E 's/^gradle-(.*)-(bin|all)\.zip$/\1/')
fi

say "== Release docs version check =="
say "README file           : $README ($(wc -c < "$README" | tr -d ' ') bytes)"
say "Install coordinate    : ${GROUP_ID}:blue-falcon-sdbus:${INSTALL_VERSION} ($COORD_COUNT occurrence(s) in README)"
say "Named release (README): $NAMED_VERSION"
say "Version being released: $EXPECT_VERSION (from ${GRADLE_PROPERTIES})"
say "POM compared against  : $POM_SOURCE"
say "POM identity          : ${POM_GROUP}:${POM_ARTIFACT}:${POM_VERSION} ($POM_BYTES bytes, $POM_DEPCOUNT dependencies)"
say "Compatibility rows    : $TABLE_ROWS parsed"
say ""

MISMATCHES=0
VERIFIED_POM=0
VERIFIED_LOCAL=0
UNKNOWN_ROWS=()

for row in "${!TABLE[@]}"; do
    claimed="${TABLE[$row]}"
    if [ "$row" = "Gradle" ]; then
        # Gradle is a build-time tool and is correctly absent from the POM, so
        # the wrapper is its authority.
        if [ -z "$WRAPPER_VERSION" ]; then
            fail 2 "Cannot verify the 'Gradle' row: no gradle-<version>-bin.zip distributionUrl in '$WRAPPER_PROPERTIES'"
        fi
        if [ "$claimed" = "$WRAPPER_VERSION" ]; then
            say "$(printf '  %-8s %-22s %s' OK Gradle "README $claimed == gradle-wrapper.properties $WRAPPER_VERSION")"
            VERIFIED_LOCAL=$((VERIFIED_LOCAL + 1))
        else
            say "$(printf '  %-8s %-22s %s' MISMATCH Gradle "README $claimed != gradle-wrapper.properties $WRAPPER_VERSION")"
            echo "::error::README compatibility table says Gradle $claimed, but gradle-wrapper.properties pins $WRAPPER_VERSION"
            MISMATCHES=$((MISMATCHES + 1))
        fi
        continue
    fi

    base="${ROW_DEP[$row]:-}"
    if [ -z "$base" ]; then
        UNKNOWN_ROWS+=("$row")
        continue
    fi

    actual="${POM_DEPS[$base]:-}"
    matched="$base"
    if [ -z "$actual" ]; then
        actual="${POM_DEPS[${base}-jvm]:-}"
        matched="${base}-jvm"
    fi
    if [ -z "$actual" ]; then
        say "$(printf '  %-8s %-22s %s' MISSING "$row" "README $claimed but the POM declares no $base")"
        echo "::error::README compatibility table lists $row $claimed, but the POM at $POM_SOURCE declares no dependency on $base"
        MISMATCHES=$((MISMATCHES + 1))
        continue
    fi

    if [ "$claimed" = "$actual" ]; then
        say "$(printf '  %-8s %-22s %s' OK "$row" "README $claimed == POM $matched $actual")"
        VERIFIED_POM=$((VERIFIED_POM + 1))
    else
        say "$(printf '  %-8s %-22s %s' MISMATCH "$row" "README $claimed != POM $matched $actual")"
        echo "::error::README compatibility table says $row $claimed, but $POM_SOURCE published $matched $actual"
        MISMATCHES=$((MISMATCHES + 1))
    fi
done

if [ "${#UNKNOWN_ROWS[@]}" -gt 0 ]; then
    say ""
    for row in "${UNKNOWN_ROWS[@]}"; do
        say "  UNKNOWN  $row — this check does not know how to verify it"
    done
    fail 1 "The compatibility table has ${#UNKNOWN_ROWS[@]} row(s) this check cannot verify (${UNKNOWN_ROWS[*]}) — add them to ROW_DEP in $0 rather than letting them go unchecked"
fi

VERIFIED=$((VERIFIED_POM + VERIFIED_LOCAL))
say ""
say "Rows verified: $VERIFIED/$TABLE_ROWS ($VERIFIED_POM against the POM, $VERIFIED_LOCAL against gradle-wrapper.properties), mismatches: $MISMATCHES"

# A denominator that is printed but never asserted is decoration. TABLE is keyed
# on row label while TABLE_ROWS counts lines, so a duplicated label collapses
# last-write-wins and the count silently drops below the number of rows a reader
# can see in the table. Without this, a README with a wrong row ABOVE the correct
# one prints "6/7 ... mismatches: 0" and exits 0 — the exact failure this script
# exists to prevent, committed by the script itself.
# The check above catches rows LOST between the table and the comparison. It
# cannot catch a row deleted from the table itself: remove one and TABLE_ROWS
# falls with VERIFIED, so 5/5 passes and the >=5 floor never notices the lost
# coverage. The script already refuses a row it does not know (UNKNOWN_ROWS
# above); this is the symmetric half — every row it DOES know must be present.
MISSING_ROWS=()
for label in "${!ROW_DEP[@]}"; do
    [ -n "${TABLE[$label]+x}" ] || MISSING_ROWS+=("$label")
done
if [ "${#MISSING_ROWS[@]}" -gt 0 ]; then
    IFS=$'\n' MISSING_SORTED=($(printf '%s\n' "${MISSING_ROWS[@]}" | sort)); unset IFS
    fail 3 "the compatibility table is missing ${#MISSING_ROWS[@]} row(s) this check knows how to verify (${MISSING_SORTED[*]}) — a deleted row lowers the denominator with it, so the count alone cannot notice the coverage it lost; restore the row, or remove it from ROW_DEP to say deliberately that it is no longer claimed"
fi

[ "$VERIFIED" -eq "$TABLE_ROWS" ] || fail 3 "verified $VERIFIED of $TABLE_ROWS table rows — every row must be accounted for, and this gap means rows were lost before comparison (most likely a duplicated row label collapsing onto one entry), so 'mismatches: $MISMATCHES' describes only the rows that survived"

# ---------------------------------------------------------------------------
# 6. The integration-test count quoted in the docs. Not a POM claim, but the
#    same drift by the same mechanism: a literal restated in prose that nothing
#    re-derives. The authority is the number of @Test functions in the
#    integration-test sources.
# ---------------------------------------------------------------------------

[ -d "$TESTS_DIR" ] || fail 3 "integration-test sources not found at '$TESTS_DIR' — the test-count check has nothing to count"

TEST_COUNT=$(grep -rE '^[[:space:]]*@Test\b' "$TESTS_DIR" | wc -l | tr -d ' ')
[ "$TEST_COUNT" -ge 1 ] || fail 3 \
    "counted 0 @Test functions under '$TESTS_DIR' — a count of zero means the pattern stopped matching, not that the suite is empty"

# Positive control: the literal must actually appear somewhere, or this whole
# section is a grep over input that cannot match and would report clean forever.
COUNT_HITS=0
COUNT_MISMATCHES=0
say ""
say "Integration-test count : $TEST_COUNT @Test function(s) under $TESTS_DIR"
for doc in "${COUNT_DOCS[@]}"; do
    [ -f "$doc" ] || continue
    while IFS= read -r hit; do
        [ -n "$hit" ] || continue
        loc="${hit%%:*}"
        rest="${hit#*:}"
        claimed_n=$(printf '%s' "$rest" | grep -oE '^[0-9]+')
        COUNT_HITS=$((COUNT_HITS + 1))
        if [ "$claimed_n" = "$TEST_COUNT" ]; then
            say "$(printf '  %-8s %-22s %s' OK "$doc:$loc" "claims $claimed_n == $TEST_COUNT counted")"
        else
            say "$(printf '  %-8s %-22s %s' MISMATCH "$doc:$loc" "claims $claimed_n != $TEST_COUNT counted")"
            echo "::error file=$doc,line=$loc::$doc says $claimed_n integration tests, but $TESTS_DIR defines $TEST_COUNT @Test functions"
            COUNT_MISMATCHES=$((COUNT_MISMATCHES + 1))
        fi
    done < <(grep -onE '[0-9]+ (integration |unit )?tests?\b' "$doc")
done

if [ "$COUNT_HITS" -lt 1 ]; then
    fail 3 "no integration-test count literal found in ${COUNT_DOCS[*]} — if the literal was deliberately removed, delete this section of $0 rather than leaving a grep that can never match"
fi
say "Test-count literals    : $COUNT_HITS found, $COUNT_MISMATCHES mismatched"
MISMATCHES=$((MISMATCHES + COUNT_MISMATCHES))

if [ "$MISMATCHES" -gt 0 ]; then
    fail 1 "The docs disagree with the release in $MISMATCHES place(s) — see the rows above"
fi

if [ "$VERIFIED_POM" -lt 1 ]; then
    fail 2 "No table row was compared against the POM — the check would have passed vacuously"
fi

emit_summary
echo "Release-docs check OK: $VERIFIED/$TABLE_ROWS compatibility rows verified for $EXPECT_VERSION against $POM_SOURCE; $COUNT_HITS test-count literal(s) match $TEST_COUNT @Test functions"
