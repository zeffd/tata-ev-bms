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
         CurrentCalibration CsvFormat PollPlan ZeroCheck BmsStatus AdapterCaps \
         ProtocolLadder Presets; do
    cp "app/src/main/java/com/tataev/bms/$f.java" "$OUT/src/com/tataev/bms/"
done
cp selftest/com/tataev/bms/*.java "$OUT/src/com/tataev/bms/"

javac -d "$OUT/classes" "$OUT/src/com/tataev/bms/"*.java
java -cp "$OUT/classes" com.tataev.bms.SelfTest
