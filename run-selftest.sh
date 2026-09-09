#!/bin/sh
# Self-test for the pure logic: ISO-TP reassembly, UDS decoding, scaling, the
# DID-mapping heuristic, the read-only command guard, the alert rules, the
# per-group pack map, the findings report, the log summary and the cross-drive
# history.
#
# No JUnit, no Gradle, no network - just javac and java, so it runs anywhere the
# app can be built. Android-dependent classes are replaced by the test doubles in
# selftest/.
set -e
cd "$(dirname "$0")"
OUT=build/selftest
rm -rf "$OUT"
mkdir -p "$OUT/src/com/tataev/bms"

# Pure classes from the app, plus the test doubles and the test itself.
for f in UdsCodec BmsFields DidScanner CommandGuard Alerter Reading Beeper \
         PackMap LogReader ProfileMatch Palette PackReport LogSummary PackHistory \
         CurrentCalibration CsvFormat PollPlan ConnectPlan ZeroCheck BmsStatus \
         AdapterCaps Redact PollReport GroupMoments; do
    cp "app/src/main/java/com/tataev/bms/$f.java" "$OUT/src/com/tataev/bms/"
done
cp selftest/com/tataev/bms/*.java "$OUT/src/com/tataev/bms/"

# minSdk is 24 and release lint is off (checkReleaseBuilds false), so nothing in
# the build catches an API-26 class. java.nio.file is the one that already
# shipped once: it throws NoClassDefFoundError on Android 7.x, which no IOException
# catch will hold. Fail here instead of on an owner's phone.
# The trailing dot matches a CLASS reference (java.nio.file.Files, or an import
# of one) and not prose about the trap, so a comment may still name it.
if grep -rn "java\.nio\.file\." app/src/main/java; then
    echo "java.nio.file is API 26+; minSdk is 24"
    exit 1
fi

# java.time is API 26 as well - use java.text.SimpleDateFormat / java.util.Date.
if grep -rn "java\.time\." app/src/main/java; then
    echo "java.time is API 26+; minSdk is 24"; exit 1
fi

# Same trap, different class: java.lang.String.join arrived on Android at API 26
# (Java 8 LIBRARY methods did; the language features came at 24), and core
# library desugaring is off because it needs a dependency. Use
# android.text.TextUtils.join in Android classes, a StringBuilder in pure ones.
if grep -rn "String\.join(" app/src/main/java; then
    echo "String.join is API 26+; minSdk is 24 - use TextUtils.join"
    exit 1
fi

# The rest of the API-26 library surface: collection factories, Optional and
# streams. Code only - the trailing paren / dot keeps prose out of it.
if grep -rnE '\b(List|Map|Set)\.of\(|Optional<|Optional\.|\.stream\(\)|Collectors\.|\.isBlank\(\)|\.strip\(\)|\.repeat\(|\.formatted\(' app/src/main/java; then
    echo "API 26+ library call; minSdk is 24"
    exit 1
fi

# BMS-only: the VECU mirror, the session fallback and the tile picker were
# removed on 2026-09-07 and must not creep back.
if grep -rnE 'VECU_MIRROR|looksLikeMirror|plausibleMirror|MirrorUse|probeBmsFirst|undoMirrorPreset|fallbackDid|onFallback|askOnMirror|unresolvedMirrorFallback|ScanStore|MappingReport|candidatesFor|capCandidates|openPicker|showPicker|applyCandidate|requestMapReset|hold to map|hold any tile|long-press a tile|pick its code|ProtocolLadder|BMS_CANDIDATES|detectBms|sweepCandidates|discoverEcus|ATTP[789]|isExtendedRequestId|forDialect|identify30xx|presetName|bmsRequestId|bmsProtocol|Share detection report|ATCP|Presets\b|probeDialect|plausibleBattery|ProgressSink|BmsInfo|applyPreset|mayApplyPreset|saveDetectReport|discoveredEcus|detectReportPath' app/src/main/java; then
    echo "FAIL: removed BMS-only feature symbol found above"; exit 1
fi

# UTF-8 explicitly: the sources carry ±, · and Ω, and javac otherwise takes the
# platform default, which on a C-locale runner is US-ASCII and fails to compile.
javac -encoding UTF-8 -d "$OUT/classes" "$OUT/src/com/tataev/bms/"*.java
java -Dfile.encoding=UTF-8 -cp "$OUT/classes" com.tataev.bms.SelfTest
