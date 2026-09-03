package com.tataev.bms;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Self-test for the pure logic, run by {@code ../run-selftest.sh}.
 *
 * The fixtures are REAL responses captured from a 2023 Nexon EV Max over an
 * ELM327, so the decoder is checked against what the car actually sends rather
 * than against invented bytes.
 */
public final class SelfTest {

    private static int pass, fail;

    private static void check(String what, Object got, Object want) {
        boolean ok = (got == null) ? want == null : got.equals(want);
        System.out.printf("  %-52s got=%-20s %s%n", what, String.valueOf(got),
                ok ? "OK" : "FAIL(want " + want + ")");
        if (ok) pass++; else fail++;
    }

    /** Widths resolved from the real field map, exactly as the service does. */
    private static final UdsCodec.WidthLookup W = did -> {
        BmsFields.Field f = BmsFields.byDid(did);
        return f == null ? -1 : BmsFields.width(f.kind);
    };

    /** Build an ELM327-style single frame: id + length + payload. */
    private static String frame(String id, String payloadHex) {
        return id + String.format("%02X", payloadHex.length() / 2) + payloadHex;
    }

    private static DidScanner.Hit hit(String did, int len, String a, String b)
            throws Exception {
        Constructor<DidScanner.Hit> c = DidScanner.Hit.class.getDeclaredConstructor(
                String.class, int.class, String.class, String.class);
        c.setAccessible(true);
        return c.newInstance(did, len, a, b);
    }

    private static String ascii(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) if (x >= 32 && x < 127) sb.append((char) x);
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        decoding();
        malformed();
        isoTpSequence();
        batching();
        mapping();
        commandGuard();
        alerting();
        adapterReject();
        packMap();
        zeroBasedPack();
        deviationBaseline();
        scanTruncationReported();
        logReader();
        replayDerivesGroupCount();
        swapAwareReplay();
        profileMatch();
        seededPackMap();
        scanLinkLoss();
        classification();
        discovery();
        seriesCountStability();
        findings();
        socContext();
        packReport();
        logSummary();
        packHistory();
        csvContract();
        carriedSoc();
        kneeAlert();
        auxAlert();
        verdictAlerts();
        calibration();
        imbalancedPack();
        twoSuspects();
        csvRows();
        pollPlan();
        kneeIrCorrection();
        emptyGridAndThreshold();
        zeroCheck();
        requestIdBound();
        negativeCurrentScale();
        vinFold();

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- decoding

    private static void decoding() {
        System.out.println("=== decoding real captures ===");
        String f197 = "78D101B62F197424D53\n78D2120202020202020\n"
                    + "78D2220202020202020\n78D2320202020202020";
        UdsCodec.Response r = UdsCodec.decode22(f197, "78D");
        check("F197 did", r.did, "F197");
        check("F197 name", ascii(r.data).trim(), "BMS");

        // Same frame structure as the real capture (a responsePending frame
        // arrives BEFORE the actual answer), with the VIN payload re-encoded to
        // a placeholder - the real one has no place in a public repo.
        String vin = "7EB037F2278\n7EB101462F1904D4154\n"
                   + "7EB2136313233343554\n7EB2245535430303030";
        UdsCodec.Response v = UdsCodec.decode22(vin, "7EB");
        check("pending does not mask answer", v.negative, false);
        check("VIN", ascii(v.data).trim(), "MAT612345TEST0000");

        check("nrc 0x31", UdsCodec.decode22("78D037F2231", "78D").nrc, 0x31);
        check("SOC scaling", BmsFields.decode(BmsFields.byDid("3402"),
                UdsCodec.decode22("78D056234020375", "78D").data, 0.01, 32000), 88.5);
        check("pack scaling", BmsFields.decode(BmsFields.byDid("3400"),
                UdsCodec.decode22("78D056234000D82", "78D").data, 0.01, 32000), 345.8);
        check("cell mV", BmsFields.decode(BmsFields.byDid("3417"),
                UdsCodec.decode22("78D056234170CF4", "78D").data, 0.01, 32000), 3316.0);
        check("temp -40 offset", BmsFields.decode(BmsFields.byDid("3409"),
                UdsCodec.decode22("78D0462340948", "78D").data, 0.01, 32000), 32.0);
        // Current, against the zero point MEASURED on the car: when the
        // contactors opened, 3401 read exactly 0x7D00 = 32000 while the link
        // voltage collapsed to 1.6 V, so 32000 is zero amps. The old 0x8000 guess
        // made a parked car report -7.6 A.
        Double zeroAmps = BmsFields.decode(BmsFields.byDid("3401"),
                UdsCodec.decode22("78D056234017D00", "78D").data, 0.1, 32000);
        check("contactors-open raw reads exactly zero amps", zeroAmps, 0.0);
        Double amps = BmsFields.decode(BmsFields.byDid("3401"),
                UdsCodec.decode22("78D056234017D07", "78D").data, 0.1, 32000);
        check("parked-but-awake draw", Math.round(amps * 100.0) / 100.0, 0.7);
        // The old assumption, kept as a regression guard.
        Double old = BmsFields.decode(BmsFields.byDid("3401"),
                UdsCodec.decode22("78D056234017D07", "78D").data, 0.01, 32768);
        check("old 0x8000 zero point is what produced -7.61",
                Math.round(old * 100.0) / 100.0, -7.61);

        // 3413 is signed. Unsigned it read 65000 for every sample of the first
        // on-vehicle log; as i16 it is -536, which is what the desktop sweep found.
        Double cur2 = BmsFields.decode(BmsFields.byDid("3413"),
                UdsCodec.decode22("78D05623413FDE8", "78D").data, 0.1, 32000);
        check("3413 decodes signed", cur2, -536.0);
        check("3413 kind is signed", BmsFields.byDid("3413").kind,
                BmsFields.Kind.I16_RAW);

        // 3410 held 0x28 for every sample while the real probes read 29-30 C, so
        // it must not print a confident "0 C".
        check("3410 is no longer decoded as a temperature",
                BmsFields.byDid("3410").kind, BmsFields.Kind.U8_RAW);
        check("3410 key names the DID, not a sensor",
                BmsFields.byDid("3410").key, "d3410");
        // 34D5 matched the computed spread on 20 of 36 samples and differed by
        // exactly 1 mV on the rest - the skew between two batched reads.
        check("34D5 is named as the BMS-reported spread",
                BmsFields.byDid("34D5").key, "cell_delta_bms_mv");

        // Resolved on the loaded drive - the names must now say what each is.
        check("3492 is the 12 V aux rail", BmsFields.byDid("3492").key, "aux_12v_v");
        check("3492 shows on the dashboard", BmsFields.byDid("3492").primary, true);
        check("3492 decodes mV to volts", BmsFields.decode(BmsFields.byDid("3492"),
                new byte[]{(byte) 0x35, (byte) 0xBD}, 0.1, 32000), 13.757);
        check("3484 is pack volts at 1 V", BmsFields.byDid("3484").key, "pack_v_1v");
        check("3482 is the link, not a second pack sense point",
                BmsFields.byDid("3482").key, "link_v");
        check("347F/3480 are accumulators", BmsFields.byDid("347F").key + "/"
                + BmsFields.byDid("3480").key, "accum_a/accum_b");
        // It held 0xFDE8 through a 100 A swing, so the old "cur_raw2" name was
        // a guess the data refuted.
        check("3413 no longer claims to be a current",
                BmsFields.byDid("3413").key, "d3413");

        // The cell indices are FLIPPED relative to DID adjacency. Proven by sign
        // reversal on a logged drive: a high-resistance group sags on discharge
        // and rises on charge, so it must swap indices when current reverses -
        // and group 77 did (341A on discharge, 3419 on regen). The old labelling
        // would have required a negative internal resistance.
        check("341A is the MINIMUM index", BmsFields.byDid("341A").key, "cell_min_idx");
        check("3419 is the MAXIMUM index", BmsFields.byDid("3419").key, "cell_max_idx");
        // The alerter keys off the ROLE, so the lock-on diagnosis follows the flip
        // automatically - it must never be wired to a DID directly.
        check("min-index role resolves to 341A",
                BmsFields.effectiveDid(BmsFields.byDid("341A"), null), "341A");

        // Another ECU's reply must never be attributed to the one we asked.
        check("other ECU ignored", UdsCodec.decode22("70A056234020375", "78D"), null);

        // A late reply echoing a DIFFERENT DID must not be taken as the answer:
        // a timed-out SOC read followed by the pack-voltage reply would otherwise
        // be recorded as "SOC 345.8 %".
        String packReply = "78D056234000D82";
        check("matching DID accepted",
                UdsCodec.decode22(packReply, "78D", "3400").did, "3400");
        check("mismatched DID rejected",
                UdsCodec.decode22(packReply, "78D", "3402"), null);
    }

    // --------------------------------------------------------------- malformed

    private static void malformed() {
        System.out.println("\n=== malformed frames must not crash ===");
        String[] nasty = {
                "78D10", "78D1", "78D", "78D10FF", "78D2", "78D21AA",
                "78D00", "", "ZZZ1234", "78D1000",
        };
        int crashes = 0;
        for (String bad : nasty) {
            try {
                UdsCodec.decode22(bad, "78D");
                splitAsAppDoes(bad, "78D", W);
                UdsCodec.reassemble(bad, 3);
            } catch (RuntimeException e) {
                crashes++;
                System.out.println("    CRASH on " + bad + ": " + e);
            }
        }
        check("no crash on 10 malformed inputs", crashes, 0);

        // An empty partial must not shadow a good single-frame reply.
        String mixed = frame("78D", "6234020375") + "\n78D10";
        UdsCodec.Response m = UdsCodec.decode22(mixed, "78D");
        check("good reply survives empty partial", m == null ? null : m.did, "3402");

        // A REAL orphaned partial: a first frame with data whose consecutive
        // frames never arrive, landing AFTER a complete reply on the same id.
        // Appended blindly it would sort last and outrank the good answer.
        String orphan = frame("78D", "6234020375") + "\n78D1018" + "62F190" + "414243";
        UdsCodec.Response o = UdsCodec.decode22(orphan, "78D");
        check("complete reply beats truncated partial",
                o == null ? null : o.did, "3402");

        // With no complete reply present, the partial should still be surfaced.
        UdsCodec.Response only = UdsCodec.decode22("78D1018" + "62F190" + "414243", "78D");
        check("lone partial still surfaced", only == null ? null : only.did, "F190");

        // A first frame carrying MORE than it declares is malformed; it used to be
        // surfaced at the frame's length rather than the declared one. Declared 3,
        // carries 4 - the trailing 0x41 must go.
        UdsCodec.Response over = UdsCodec.decode22("78D1003" + "62F190" + "41", "78D");
        check("over-long first frame trimmed to declared length",
                over == null || over.data == null ? -1 : over.data.length, 0);
        // ...and it still must not outrank a good answer on the same id.
        UdsCodec.Response beaten = UdsCodec.decode22(
                frame("78D", "6234020375") + "\n78D1003" + "62F190" + "41", "78D");
        check("good reply beats over-long first frame",
                beaten == null ? null : beaten.did, "3402");
    }

    // ------------------------------------------------------- ISO-TP sequencing

    private static void isoTpSequence() {
        System.out.println("\n=== ISO-TP sequence numbers ===");
        // 24 bytes: 62 F190 + 21 payload bytes, as FF + CF1 + CF2 + CF3.
        String ff = "78D1018" + "62F190" + "414243";      // 0x18 = 24 total
        String cf1 = "78D21" + "44454647484950";
        String cf2 = "78D22" + "51525354555657";
        String cf3 = "78D23" + "58596061626364";
        UdsCodec.Response ok = UdsCodec.decode22(ff + "\n" + cf1 + "\n" + cf2 + "\n" + cf3, "78D");
        check("ordered CFs assemble", ok == null ? null : ok.did, "F190");

        // A dropped CF used to be concatenated silently, shifting every later
        // byte and producing plausible but WRONG values.
        UdsCodec.Response gap = UdsCodec.decode22(ff + "\n" + cf1 + "\n" + cf3, "78D");
        String gapData = (gap == null || gap.data == null) ? "" : ascii(gap.data);
        check("dropped CF is not silently accepted",
                gapData.contains("58596061"), false);

        // Out-of-order delivery must be rejected too.
        UdsCodec.Response ooo = UdsCodec.decode22(ff + "\n" + cf2 + "\n" + cf1, "78D");
        String oooData = (ooo == null || ooo.data == null) ? "" : ascii(ooo.data);
        check("reordered CF rejected", oooData.contains("51525354"), false);
    }

    // ---------------------------------------------------------------- batching

    /**
     * Split a batched reply exactly the way ElmClient.readDidBatch does.
     *
     * UdsCodec used to carry a decode22Batch() convenience wrapper that composed
     * reassemble() and splitBatch() in one call. Nothing in the app used it - the
     * app needs the intermediate payload to tell a silent link from an
     * unsplittable reply - so the only caller was this file, and the test was
     * exercising a composition production code never runs. The wrapper is gone;
     * this helper composes the same two calls the service does.
     */
    private static Map<String, byte[]> splitAsAppDoes(String text, String id,
                                                      UdsCodec.WidthLookup widths) {
        return UdsCodec.splitBatch(UdsCodec.reassemble(text, 3).get(id), widths);
    }

    private static void batching() {
        System.out.println("\n=== batched reply splitting ===");
        String p = "62" + "3402" + "0375" + "3403" + "03BA" + "3400" + "0D82";
        Map<String, byte[]> b = splitAsAppDoes(frame("78D", p), "78D", W);
        check("three u16 DIDs", b == null ? -1 : b.size(), 3);
        check("value intact", UdsCodec.toHex(b.get("3400")), "0D82");

        String q = "62" + "3417" + "0CF4" + "3419" + "34" + "341A" + "01";
        Map<String, byte[]> b2 = splitAsAppDoes(frame("78D", q), "78D", W);
        check("mixed widths", b2 == null ? -1 : b2.size(), 3);
        check("u8 index", UdsCodec.toHex(b2.get("3419")), "34");

        check("omitted DID tolerated", splitAsAppDoes(
                frame("78D", "6234020375" + "34000D82"), "78D", W).size(), 2);
        check("unknown DID refused", splitAsAppDoes(
                frame("78D", "623402037599991234"), "78D", W), null);
        check("trailing junk refused", splitAsAppDoes(
                frame("78D", "62341934FF"), "78D", W), null);
        check("negative reply is not a batch",
                splitAsAppDoes("78D037F2231", "78D", W), null);
    }

    // ----------------------------------------------------------------- mapping

    private static void mapping() throws Exception {
        System.out.println("\n=== DID mapping heuristic vs ground truth ===");
        // Real two-sample observations from the car.
        List<DidScanner.Hit> hits = new ArrayList<>(Arrays.asList(
                hit("3400", 2, "0D87", "0D88"),   // pack V   (ambiguous by design)
                hit("3401", 2, "7D06", "7D06"),
                hit("3402", 2, "0375", "0378"),   // SOC, moves
                hit("3403", 2, "03BA", "03BA"),   // SOH, steady
                hit("3409", 1, "49", "49"),       // temps, steady and clustered
                hit("340B", 1, "47", "47"),
                hit("3411", 1, "47", "47"),
                hit("3412", 1, "47", "47"),
                hit("3415", 2, "0D00", "0D02"),   // cell max
                hit("3417", 2, "0CF7", "0CF8"),   // cell min
                hit("3419", 1, "05", "26"),       // MAX idx (proven by sign reversal)
                hit("341A", 1, "01", "23"),       // MIN idx
                hit("3482", 2, "0D85", "0D86")));
        Map<String, String> s = DidScanner.suggest(hits);
        check("soc_pct", s.get("soc_pct"), "3402");
        check("soh_pct", s.get("soh_pct"), "3403");
        // The higher DID carries the MINIMUM. Assigning by list position instead -
        // which is ascending DID order - silently reproduced the pre-v3.3 mapping
        // and would have inverted the dashboard, the alerter, the pack map and
        // every CSV row written after tapping "Apply suggested mapping".
        check("cell_min_idx is the higher DID", s.get("cell_min_idx"), "341A");
        check("cell_max_idx is the lower DID", s.get("cell_max_idx"), "3419");

        // THE GUARD THAT MATTERS: the scan heuristic and the shipped field map
        // must never disagree about which DID plays which role. Two assertions
        // stating opposite things both passed before, because they tested
        // different code paths and nothing compared them.
        check("suggest() agrees with BmsFields on the min index",
                s.get("cell_min_idx"),
                BmsFields.effectiveDid(BmsFields.byDid("341A"), null));
        check("suggest() agrees with BmsFields on the max index",
                s.get("cell_max_idx"),
                BmsFields.effectiveDid(BmsFields.byDid("3419"), null));
        check("...and BmsFields still calls 341A the minimum",
                BmsFields.byDid(s.get("cell_min_idx")).key, "cell_min_idx");
        check("...and 3419 the maximum",
                BmsFields.byDid(s.get("cell_max_idx")).key, "cell_max_idx");

        // A lone index candidate has no partner to be ordered against, so it must
        // be left unmapped rather than guessed into a role.
        List<DidScanner.Hit> one = new ArrayList<>(Arrays.asList(
                hit("3415", 2, "0D00", "0D02"),
                hit("3417", 2, "0CF7", "0CF8"),
                hit("3400", 2, "0D87", "0D88"),
                hit("3419", 1, "05", "26")));
        Map<String, String> s1 = DidScanner.suggest(one);
        check("a single index candidate is not guessed into a role",
                s1.get("cell_min_idx"), null);

        // temp slots come from the field map, so a role it does not have cannot
        // be emitted into an override nothing reads.
        for (String k : s.keySet()) {
            if (k.startsWith("temp_")) {
                boolean known = false;
                for (BmsFields.Field f : BmsFields.ALL) if (f.key.equals(k)) known = true;
                check("emitted temp role exists in BmsFields: " + k, known, true);
            }
        }
        check("no temp_e_c role is emitted", s.get("temp_e_c"), null);
        check("no silent pack_v guess", s.get("pack_v"), null);
        check("no silent cell_max guess", s.get("cell_max_mv"), null);

        // A moving byte must never be offered as a temperature.
        boolean movingByteAsTemp = false;
        for (String k : new String[]{"temp_a_c", "temp_b_c", "temp_c_c",
                                     "temp_d_c", "temp_e_c"}) {
            String did = s.get(k);
            if ("3419".equals(did) || "341A".equals(did)) movingByteAsTemp = true;
        }
        check("moving bytes not mapped as temperature", movingByteAsTemp, false);
        check("a temperature was found", s.get("temp_a_c") != null, true);

        System.out.println("\n=== voltage hypotheses must be offered, not guessed ===");
        List<DidScanner.VoltageHypothesis> vh = DidScanner.voltageHypotheses(hits);
        boolean truthOffered = false;
        for (DidScanner.VoltageHypothesis h : vh) {
            System.out.println("    " + h.describe());
            if (h.cellMaxDid.equals("3415") && h.cellMinDid.equals("3417")
                    && (h.packDid.equals("3400") || h.packDid.equals("3482"))) {
                truthOffered = true;
            }
        }
        check("correct reading is among the options", truthOffered, true);
        check("more than one reading offered (hence the choice)", vh.size() > 1, true);
    }

    // ------------------------------------------------------------ read-only

    private static void commandGuard() {
        System.out.println("\n=== read-only command guard ===");
        check("read allowed", CommandGuard.isAllowed("22F190"), true);
        check("batched read allowed", CommandGuard.isAllowed("22F1903402"), true);
        check("AT allowed", CommandGuard.isAllowed("ATSH 785"), true);
        check("extended session allowed", CommandGuard.isAllowed("1003"), true);
        check("default session allowed", CommandGuard.isAllowed("1001"), true);
        check("tester present allowed", CommandGuard.isAllowed("3E00"), true);

        check("write refused", CommandGuard.isAllowed("2E010203"), false);
        check("clear DTCs refused", CommandGuard.isAllowed("14FFFFFF"), false);
        check("ECU reset refused", CommandGuard.isAllowed("1101"), false);
        check("routine control refused", CommandGuard.isAllowed("31010203"), false);
        check("IO control refused", CommandGuard.isAllowed("2F0102"), false);
        check("security access refused", CommandGuard.isAllowed("2701"), false);
        check("programming session refused", CommandGuard.isAllowed("1002"), false);
        // A CR would terminate the first command and run the rest as a second one.
        check("CR-smuggled write refused",
                CommandGuard.isAllowed("22F190\r2E010203"), false);
        check("LF-smuggled write refused",
                CommandGuard.isAllowed("22F190\n2E010203"), false);
        check("non-hex read refused", CommandGuard.isAllowed("22ZZZZ"), false);
        // A bare CR makes the ELM327 repeat its previous command - an effect the
        // guard cannot inspect, so it must not be blessed.
        check("empty command refused", CommandGuard.isAllowed(""), false);
        check("whitespace-only command refused", CommandGuard.isAllowed("   "), false);
        check("null command refused", CommandGuard.isAllowed(null), false);
        check("bare 22 refused", CommandGuard.isAllowed("22"), false);
        // Character.digit() reads a fullwidth digit as its ASCII value, so these
        // used to validate as hex and then go out as UTF-8 the adapter rejects.
        check("fullwidth-digit read refused",
                CommandGuard.isAllowed("22３４０２"), false);
        check("arabic-indic-digit read refused",
                CommandGuard.isAllowed("22٣٤٠٢"), false);
        check("ascii hex still accepted", CommandGuard.isHex("3402aF"), true);
        check("non-ascii hex refused", CommandGuard.isHex("７８５"), false);

        // "Starts with AT" used to be blanket-allowed on the reasoning that AT is
        // adapter-local. It is not: these three reach the bus or the adapter's
        // persistent settings.
        check("ATSH with id allowed", CommandGuard.isAllowed("ATSH 785"), true);
        check("ATCRA with id allowed", CommandGuard.isAllowed("ATCRA78D"), true);
        check("ATFCSD allowed", CommandGuard.isAllowed("ATFCSD 300000"), true);
        check("ATRTR refused (transmits a frame)",
                CommandGuard.isAllowed("ATRTR"), false);
        check("ATMA refused (puts the adapter on the bus)",
                CommandGuard.isAllowed("ATMA"), false);
        check("ATPP write refused (persistent adapter config)",
                CommandGuard.isAllowed("ATPP2CSV01"), false);
        check("ATSW refused", CommandGuard.isAllowed("ATSW00"), false);
        check("unknown AT refused", CommandGuard.isAllowed("ATQQ"), false);
        check("ATSH with non-hex id refused",
                CommandGuard.isAllowed("ATSH 78G"), false);
        check("ATSH with wrong-length id refused",
                CommandGuard.isAllowed("ATSH 7850"), false);
    }

    // -------------------------------------------------------------- alerting

    /** Drives {@link Alerter} through a scripted clock, with a counting beeper. */
    private static final class FakeBeeper implements Beeper {
        int beeps;

        @Override
        public void beep() {
            beeps++;
        }

        @Override
        public void release() {
        }
    }

    private static Reading reading(double minMv, double maxMv, Double minIdx) {
        Reading r = new Reading(0L);
        r.values.put("cell_min_mv", minMv);
        r.values.put("cell_max_mv", maxMv);
        if (minIdx != null) r.values.put("cell_min_idx", minIdx);
        r.computeDerived();
        return r;
    }

    /**
     * The alert rules - the only logic in this app a driver would rely on, and
     * until now the only logic the self-test could not reach at all.
     */
    private static void alerting() {
        System.out.println("\n=== alert rules ===");
        Prefs prefs = new Prefs();
        prefs.deltaLimit = 50;
        prefs.minCellLimit = 3000;

        // A healthy pack: 10 mV spread, weakest group wandering.
        FakeBeeper beeper = new FakeBeeper();
        long[] now = {1_000_000L};
        Alerter a = new Alerter(beeper, () -> now[0]);
        for (int i = 0; i < 60; i++) {
            a.evaluate(reading(3320, 3330, (double) (i % 13 + 1)), prefs);
        }
        check("healthy pack does not alert", a.activeReason(), null);
        check("healthy pack does not beep", beeper.beeps, 0);

        // Spread over the limit fires; strictly greater, matching the dashboard.
        a = new Alerter(beeper = new FakeBeeper(), () -> now[0]);
        check("delta exactly at the limit does not fire",
                a.evaluate(reading(3300, 3350, 7.0), prefs), null);
        String fired = a.evaluate(reading(3300, 3351, 7.0), prefs);
        check("delta over the limit fires", fired != null, true);
        check("...and beeps", beeper.beeps, 1);
        // The re-arm window must survive a freshly booted phone, where
        // elapsedRealtime starts near zero.
        now[0] += 5_000L;
        check("re-alert inside the re-arm window is silent",
                a.evaluate(reading(3300, 3360, 7.0), prefs), null);
        check("...and does not beep again", beeper.beeps, 1);
        now[0] += 20_000L;
        check("re-alert after the re-arm window fires",
                a.evaluate(reading(3300, 3360, 7.0), prefs) != null, true);
        check("...and beeps again", beeper.beeps, 2);

        // One group holding the minimum used to be an alert of its own
        // ("lock-on"), re-armed every 20 s whenever the spread was over 25 mV -
        // which on a real pack is any long highway pull. Verdicts are announced
        // once instead (see verdictAlerts). The tally survives as a summary.
        a = new Alerter(beeper = new FakeBeeper(), () -> now[0]);
        for (int i = 0; i < 40; i++) a.evaluate(reading(3300, 3330, 37.0), prefs);
        check("a persistent weakest group alone no longer alerts", a.activeReason(), null);
        check("...and does not beep", beeper.beeps, 0);
        check("weakest tally is still published",
                a.weakestSummary(), "weakest: group 37 - 100% of last 40");
        a.reset();
        check("reset clears the tally", a.weakestSummary(), "");

        // The delta limit follows the pack's own rest spread, floored at the
        // configured number: 50 mV on a pack resting at 10 mV, 90 on one at 30.
        // There is no threshold field an owner could have set this from.
        Alerter ad = new Alerter(new FakeBeeper(), () -> now[0]);
        check("no rest spread known: the floor applies", ad.deltaLimitMv(prefs), 50);
        ad.setRestSpreadMv(10);
        check("a tight pack keeps the floor", ad.deltaLimitMv(prefs), 50);
        ad.setRestSpreadMv(30);
        check("a loose pack lifts the limit to three times its rest spread",
                ad.deltaLimitMv(prefs), 90);
        check("60 mV on that pack is not an alert",
                ad.evaluate(reading(3300, 3360, 7.0), prefs), null);
        check("91 mV is", ad.evaluate(reading(3300, 3391, 7.0), prefs) != null, true);

        // Alerts switched off silences the tone but must NOT hide the reason -
        // the banner and the toast are driven by activeReason().
        prefs.alerts = false;
        FakeBeeper quiet = new FakeBeeper();
        a = new Alerter(quiet, () -> now[0]);
        a.evaluate(reading(3300, 3400, 7.0), prefs);
        check("alerts off does not beep", quiet.beeps, 0);
        check("alerts off still reports the reason", a.activeReason() != null, true);
    }

    // ------------------------------------------------------------ pack map

    /**
     * A pack that numbers its cell groups from 0 rather than 1.
     *
     * PackMap.add used to reject any sample whose min OR max index was 0, which
     * threw the whole reading away - both ends - and left group 0 permanently
     * invisible. On a model numbered from zero that is a large share of the
     * samples, on the one screen built for models whose map differs from the
     * Nexon's.
     */
    private static void zeroBasedPack() {
        System.out.println("\n=== a pack numbered from zero ===");
        PackMap m = new PackMap();
        // Group 0 is the resistive one, so it holds an extreme in every sample.
        for (double amps : sweep(-70, 110, 120)) {
            double packMv = 0, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            int loI = 0, hiI = 0;
            for (int g = 0; g < 104; g++) {
                double r = (g == 0) ? 1.00 : 0.70;
                double v = 3330.0 - amps * r;
                packMv += v;
                if (v < lo) { lo = v; loI = g; }
                if (v > hi) { hi = v; hiI = g; }
            }
            m.add(packMv / 1000.0, lo, hi, loI, hiI, amps);
        }
        check("zero-based samples are kept, not discarded", m.samples(), 120);
        check("the pack is recognised as zero-based", m.firstIndex(), 0);
        check("group 0 was seen", m.group(0).seen(), true);
        check("group 0 is measurable", m.group(0).measurable(), true);
        check("group 0 can be the weakest", m.weakestGroup(), 0);
        check("104 groups still counted as 104", m.seriesCount(), 104);
        check("last index is 103, not 104", m.lastIndex(), 103);

        PackMap one = new PackMap();
        one.add(345.0, 3300, 3400, 1, 2, 40.0);
        check("a 1-based pack is unaffected", one.firstIndex(), 1);
    }

    /**
     * The deviation baseline must stop moving once it has been established.
     *
     * seriesCount is re-derived per sample, from pack volts over mean cell volts,
     * and lands on 104 or 105 depending on rounding. One step of that changes the
     * per-group average by ~32 mV - and because the mean cell voltage it is
     * derived from SAGS UNDER LOAD, the flip correlates with current. A 32 mV
     * shift correlated across a 180 A span is ~0.18 mOhm of pure artefact, which
     * on a real 0.30 mOhm signal is most of the answer.
     *
     * Only reachable through add(Reading): that is the path carrying
     * impliedSeries, and it is the path the service uses.
     */
    private static void deviationBaseline() {
        System.out.println("\n=== deviation baseline is pinned ===");
        PackMap m = new PackMap();
        for (double amps : sweep(-70, 110, 200)) {
            double packMv = 0, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            int loI = 1, hiI = 1;
            for (int g = 1; g <= 104; g++) {
                double r = (g == 77) ? 1.00 : 0.70;
                double v = 3330.0 - amps * r;
                packMv += v;
                if (v < lo) { lo = v; loI = g; }
                if (v > hi) { hi = v; hiI = g; }
            }
            Reading r = new Reading(0);
            r.values.put("pack_v", packMv / 1000.0);
            r.values.put("cell_min_mv", lo);
            r.values.put("cell_max_mv", hi);
            r.values.put("cell_min_idx", (double) loI);
            r.values.put("cell_max_idx", (double) hiI);
            r.values.put("current_a", amps);
            // The flip, tied to load exactly as the real derivation ties it.
            r.impliedSeries = amps > 20 ? 104.6 : 104.4;
            m.add(r);
        }
        double excess = m.group(77).excessMilliOhm();
        // True excess is 1.00 - 0.70 = 0.30 mOhm. Un-pinned, the load-correlated
        // baseline flip drags this far off.
        check("fitted excess survives a load-correlated series flip",
                Math.abs(excess - 0.30) < 0.02, true);
        check("...and is still close to 0.30", Math.round(excess * 100) / 100.0, 0.3);
    }

    /**
     * A hypothesis search that had to drop responders must SAY so.
     *
     * DidScanner counted the drop and a comment claimed truncation "is reported
     * rather than silent" - but nothing rendered scanNote(), so a shortlist built
     * from part of the evidence looked exactly like one built from all of it.
     */
    private static void scanTruncationReported() {
        System.out.println("\n=== truncated scan analysis is reported ===");
        List<DidScanner.Hit> few = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            few.add(new DidScanner.Hit(String.format("%04X", 0x3400 + i), 2,
                    String.format("%04X", 3300 + i), null));
        }
        check("an exhaustive search says nothing", DidScanner.analyse(few).note(), "");

        List<DidScanner.Hit> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(new DidScanner.Hit(String.format("%04X", 0x3400 + i), 2,
                    String.format("%04X", 3300 + (i % 40)), null));
        }
        DidScanner.Analysis big = DidScanner.analyse(many);
        check("a truncated search reports the drop", big.note().contains("left out"), true);
        check("...and says how many", big.dropped, 60);
        // The count travels WITH its analysis, so an earlier one cannot leak into
        // a later report.
        check("an exhaustive search after a truncated one is still silent",
                DidScanner.analyse(few).note(), "");
        check("...and the truncated analysis still knows its own count",
                big.dropped, 60);
    }


    /**
     * A synthetic pack where exactly one group is resistive.
     *
     * Every group holds the same open-circuit voltage except one that is simply
     * low on charge, and one that carries extra resistance. Under discharge the
     * resistive group sags furthest; under regen it rises highest. That is the
     * behaviour the real car showed, and it is what makes the group measurable.
     */
    private static PackMap syntheticPack(int n, int resistive, int lowCharge,
                                         double[] currents) {
        PackMap m = new PackMap();
        double baseOcv = 3330.0, baseR = 0.70;
        for (double amps : currents) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            int loI = 1, hiI = 1;
            double packMv = 0;
            for (int g = 1; g <= n; g++) {
                double ocv = (g == lowCharge) ? baseOcv - 12 : baseOcv;
                double r = (g == resistive) ? baseR + 0.30 : baseR;
                double v = ocv - amps * r;
                packMv += v;
                if (v < lo) { lo = v; loI = g; }
                if (v > hi) { hi = v; hiI = g; }
            }
            m.add(packMv / 1000.0, lo, hi, loI, hiI, amps);
        }
        return m;
    }

    private static double[] sweep(double from, double to, int steps) {
        double[] out = new double[steps];
        for (int i = 0; i < steps; i++) out[i] = from + (to - from) * i / (steps - 1.0);
        return out;
    }

    private static void packMap() {
        System.out.println("\n=== pack coverage map ===");
        PackMap m = syntheticPack(104, 77, 12, sweep(-70, 110, 200));

        check("samples counted", m.samples(), 200);

        // A flat pack naming ONE group as both extremes must not let that group
        // look like it reached both ends.
        PackMap flat = new PackMap();
        for (int i = 0; i < 60; i++) flat.add(346.0, 3330, 3330, 7, 7, i - 30);
        check("same group at both extremes is not double-counted",
                flat.group(7).maxCount, 0);
        check("...so it never becomes measurable", flat.group(7).measurable(), false);
        check("...but it is still recorded as seen", flat.group(7).seen(), true);

        // The resistive group reaches BOTH extremes, so it can be fitted.
        check("resistive group is measurable", m.group(77).measurable(), true);
        double ex = m.group(77).excessMilliOhm();
        check("its excess resistance recovers ~0.30 mOhm",
                Math.round(ex * 100) / 100.0, 0.3);

        // The merely-undercharged group never reaches the top of the pack, so it
        // must NOT be handed a resistance - this is the selection bias guard.
        check("undercharged group was seen", m.group(12).seen(), true);
        check("...but is NOT measurable", m.group(12).measurable(), false);
        check("...and reports no resistance",
                Double.isNaN(m.group(12).excessMilliOhm()), true);

        // Whole-pack Ohm's law: 104 groups x 0.70 mOhm, plus 0.30 on one of them.
        double pack = m.packMilliOhm();
        check("pack resistance recovers ~73 mOhm", Math.round(pack), 73L);

        // Nothing may be invented for a group the BMS never named.
        check("unnamed group is UNSEEN", m.stateOf(50), PackMap.State.UNSEEN);
        check("unnamed group has no resistance",
                Double.isNaN(m.group(50).excessMilliOhm()), true);
        check("coverage is honest about the gaps", m.seenCount() < 104, true);

        // A discharge-only log can measure nothing per-group, however long it is.
        PackMap d = syntheticPack(104, 77, 12, sweep(10, 110, 300));
        check("discharge-only log: nothing measurable", d.measurableCount(), 0);
        check("discharge-only log still finds the weakest", d.weakestGroup(), 77);

        // Spread separates into balance and resistance only once loaded.
        PackMap s2 = syntheticPack(104, 77, 12, sweep(-70, 110, 200));
        check("resistive part of the spread is positive",
                s2.resistiveSpreadMv() > 0, true);

        // REQUIRED_KEYS is the contract behind the CSV field selector: Settings
        // lets every other field be turned off, so prove add(Reading) really
        // does sample with exactly these present and really does refuse when
        // any ONE of them is missing. If add() ever grows a new dependency,
        // this fails and REQUIRED_KEYS must be extended with it.
        PackMap complete = new PackMap();
        complete.add(packMapReading());
        check("all REQUIRED_KEYS present -> sampled", complete.samples(), 1);
        for (String drop : PackMap.REQUIRED_KEYS) {
            PackMap partial = new PackMap();
            Reading r = packMapReading();
            r.values.remove(drop);
            partial.add(r);
            check("missing " + drop + " -> not sampled", partial.samples(), 0);
        }

        // pollOnce guarantees polling only PRIMARY fields when "log extras" is
        // off, so a required key that is not primary would starve the map on
        // every default-config drive - with this suite still green.
        for (String k : PackMap.REQUIRED_KEYS) {
            BmsFields.Field field = null;
            for (BmsFields.Field f : BmsFields.ALL) {
                if (f.key.equals(k)) field = f;
            }
            check("required key " + k + " is a primary field",
                    field != null && field.primary, true);
        }
    }

    // ------------------------------------------------------- vehicle profiles

    private static void profileMatch() {
        System.out.println("\n=== vehicle profile matching ===");
        // The REAL F190 shape: a model-year prefix and trailing zeros are all
        // VIN-alphabet characters, so position alone cannot find the VIN -
        // only Tata's MAT WMI can.
        check("VIN out of the real F190 string",
                ProfileMatch.extractVin("2023MAT612345TEST0000000"),
                "MAT612345TEST0000");
        check("bare VIN accepted",
                ProfileMatch.extractVin("MAT612345TEST0000"), "MAT612345TEST0000");
        check("lower case normalised",
                ProfileMatch.extractVin("mat612345test0000"), "MAT612345TEST0000");
        check("supplier string is not a VIN",
                ProfileMatch.extractVin("GOTION_BMS"), "");
        check("null survives", ProfileMatch.extractVin(null), "");
        check("exact 17-char run accepted as fallback",
                ProfileMatch.extractVin("VF1RFD00X66666666"), "VF1RFD00X66666666");
        check("truncated VIN rejected", ProfileMatch.extractVin("MAT612345TEST"), "");

        check("unidentifiable -> stay",
                ProfileMatch.decide("", "MAT1", false), ProfileMatch.Action.STAY);
        check("same car -> stay",
                ProfileMatch.decide("MAT1", "MAT1", false), ProfileMatch.Action.STAY);
        check("known elsewhere -> switch",
                ProfileMatch.decide("MAT2", "MAT1", true), ProfileMatch.Action.SWITCH);
        check("first identify adopts, calibration kept",
                ProfileMatch.decide("MAT1", "", false), ProfileMatch.Action.ADOPT);
        check("different car -> create",
                ProfileMatch.decide("MAT2", "MAT1", false), ProfileMatch.Action.CREATE);

        check("fingerprint from address+supplier",
                ProfileMatch.fingerprint("785", "GOTION_BMS"), "785|GOTION_BMS");
        check("no identity at all -> empty",
                ProfileMatch.fingerprint("", null), "");
        // Half an identity must be NONE: the address is always known locally, so
        // a failed supplier read would otherwise mint the pseudo-id "785|" and a
        // flaky connect would create a junk profile per hiccup.
        check("half an identity (no supplier) is none",
                ProfileMatch.fingerprint("785", ""), "");
        check("half an identity (no address) is none",
                ProfileMatch.fingerprint(null, "GOTION_BMS"), "");
    }

    /** A profile-seeded map is right from the first sample and still self-corrects. */
    private static void seededPackMap() {
        System.out.println("\n=== profile-seeded pack map ===");
        PackMap m = new PackMap();
        m.seed(96);
        check("seeded count before any sample", m.seriesCount(), 96);
        PackMap z = new PackMap();
        z.seed(96);
        check("zero-base is never seeded - it is re-derived", z.firstIndex(), 1);
        PackMap p = new PackMap();
        p.seed(255);
        check("a poisoned stored size (>250) is refused", p.seriesCount(), 104);
        // A wrong seed must be outvoted by what the car actually reports.
        PackMap w = new PackMap();
        w.seed(96);
        w.add(packMapReading());               // implies ~104 series
        check("data outvotes a wrong seed", w.seriesCount() >= 104, true);
        w.seed(50);
        check("seed after samples is ignored", w.seriesCount() >= 104, true);
    }

    /**
     * A dead link must truncate the scan LOUDLY, and silence must not.
     *
     * A dropped adapter makes every remaining read fail instantly; without
     * detection the sweep "completes" with each unasked DID recorded as a
     * non-responder - a truncated report indistinguishable from a finished one.
     * Silence from a healthy socket is different: that is an absent DID.
     */
    private static void scanLinkLoss() throws Exception {
        System.out.println("\n=== a dead link truncates the scan loudly ===");
        DidScanner dead = new DidScanner();
        ElmClient deadElm = new ElmClient();
        deadElm.dieAfter = 0;                    // every call throws
        dead.scan(deadElm, 0x3400, 0x34FF, null);
        check("dead link is detected", dead.linkLost(), true);
        check("...and the sweep stopped early", dead.linkLostAtDid() <= 0x3410, true);

        DidScanner quiet = new DidScanner();
        quiet.scan(new ElmClient(), 0x3400, 0x340F, null);   // silent but alive
        check("silence alone is NOT link loss", quiet.linkLost(), false);

        // A link that dies only once the FINAL group is being confirmed must
        // still name that group: the abort check runs at the BOTTOM of the loop
        // body precisely so the loop condition cannot skip it and report 0000.
        DidScanner tail = new DidScanner();
        ElmClient dying = new ElmClient();
        // Survives findAnchorDid's probes (F197 + every BmsFields DID), then
        // every sweep read throws: 3 errors in group 3400, the 5-error limit
        // trips inside group 3403 - the last group of the range.
        dying.dieAfter = 1 + BmsFields.ALL.size();
        tail.scan(dying, 0x3400, 0x3405, null);
        check("death reaching the final group is detected", tail.linkLost(), true);
        check("...and the dying group is named, not 0",
                tail.linkLostAtDid(), 0x3403);
    }

    /**
     * The five-state verdicts behind the redesigned pack map.
     *
     * The trap under test: SOME group always holds the minimum - under load
     * too - so a pack with no resistive outlier must not paint its low-charge
     * floor group amber. Rest-dominance plus never-maximum reads as BALANCE;
     * load-dominance without it reads as WATCH; a fitted excess reads SUSPECT.
     */
    private static void classification() throws Exception {
        System.out.println("\n=== pack map classification ===");
        // Resistive pack, discharge only: suspicious but unfittable -> WATCH,
        // once the car has stopped long enough to show who holds the floor at
        // rest (a resistive group cannot). Load dominance alone is HEALTHY.
        PackMap d = syntheticPack(104, 77, 12, sweep(10, 110, 300));
        check("load dominance alone, no rest yet -> not WATCH",
                d.stateOf(77), PackMap.State.HEALTHY);
        restStop(d, 12);
        check("resistive group on discharge-only, after a stop -> WATCH",
                d.stateOf(77), PackMap.State.WATCH);
        // With regen as well it graduates to a measured SUSPECT.
        PackMap m = syntheticPack(104, 77, 12, sweep(-70, 110, 200));
        check("resistive group with both extremes -> SUSPECT",
                m.stateOf(77), PackMap.State.SUSPECT);
        // A healthy pack's floor group holds the minimum at EVERY current;
        // it must read as charge (BALANCE), never as suspicion.
        PackMap h = new PackMap();
        boolean watchedEnRoute = false;
        for (int i = 0; i < 40; i++) {
            h.add(346, 3328, 3337, 9, 60, 80);
            // The TRAJECTORY matters, not just the end state: connected while
            // already driving, the floor group used to be WATCH - and announced
            // with a beep - after ten load minima, before the first stop.
            if (h.stateOf(9) == PackMap.State.WATCH) watchedEnRoute = true;
        }
        check("healthy floor group is never WATCH on the way", watchedEnRoute, false);
        for (int i = 0; i < 25; i++) h.add(347, 3330, 3338, 9, 60, 0.4);
        check("healthy floor group -> BALANCE, not WATCH",
                h.stateOf(9), PackMap.State.BALANCE);
        check("...and its ceiling group is HEALTHY",
                h.stateOf(60), PackMap.State.HEALTHY);

        // One skewed sample naming the floor group as maximum (the min and max
        // indices are read ~250 ms apart) must not demote it from BALANCE.
        h.add(347, 3330, 3338, 15, 9, -30);
        check("one skewed max does not demote BALANCE",
                h.stateOf(9), PackMap.State.BALANCE);

        // One glitch sample must not poison the pack's size or numbering.
        PackMap gl = new PackMap();
        gl.add(346, 3320, 3340, 12, 255, 30);          // 0xFF sentinel index
        check("sentinel index 255 is rejected outright", gl.samples(), 0);
        PackMap g2 = new PackMap();
        g2.add(346, 3320, 3340, 12, 200, 30);
        check("one spurious sighting does not raise the size",
                g2.seriesCount(), 104);
        g2.add(346, 3320, 3340, 12, 200, 30);
        check("...but a corroborated second one does",
                g2.seriesCount() >= 200, true);
        PackMap z1 = new PackMap();
        z1.add(346, 3320, 3340, 0, 50, 30);
        check("one index-0 sample does not flip zero-based", z1.firstIndex(), 1);
        z1.add(346, 3320, 3340, 0, 50, 30);
        check("...a second sighting does", z1.firstIndex(), 0);
    }

    /**
     * A NEW log whose min-index DID is 3419 is not a flipped old log - it is a
     * deliberately swapped override on a model whose ordering differs from the
     * Nexon's. The reader must trust its role columns as written; the DID
     * correction is only for files that predate the v3.3 flip.
     */
    private static void swapAwareReplay() throws Exception {
        System.out.println("\n=== a swapped-override log is not 'corrected' ===");
        java.io.File f = java.io.File.createTempFile("bmsswap", ".csv");
        f.deleteOnExit();
        java.io.PrintWriter w = new java.io.PrintWriter(f, "UTF-8");
        w.println("epoch_ms,timestamp,pack_v,current_a,cell_min_mv,cell_min_idx,"
                + "cell_max_mv,cell_max_idx,raw_cell_min_idx_3419,raw_cell_max_idx_341A,"
                + "app_ver");
        for (int i = 0; i < 30; i++) {
            w.printf(java.util.Locale.US,
                    "%d,2026-08-27 10:00:%02d,346.0,%.1f,3300,77,3340,4,4D,04,3.16%n",
                    1787630000000L + i * 1000L, i % 60, -60 + i * 4.0);
        }
        w.close();
        LogReader.Result r = LogReader.read(f);
        check("swapped-override log is NOT flip-corrected", r.flipCorrected, false);
        check("...and the weakest group is read as written",
                r.map.weakestGroup(), 77);
    }

    /**
     * The group count must not follow the load.
     *
     * Reading derives impliedSeries from pack volts over the MIDPOINT of the two
     * extreme cells. Under load the minimum sags further than the average does,
     * so the midpoint under-reads the mean and the estimate climbs: 102.1 to
     * 105.9 within one real drive. Rounding it per sample grew the grid to 105
     * or 106 squares under load and wrote the wobble to the profile.
     */
    private static void seriesCountStability() {
        System.out.println("\n=== group count does not follow the load ===");
        PackMap m = new PackMap();
        int worst = 0;
        for (double amps : sweep(-70, 110, 200)) {
            m.add(syntheticReading(104, 77, amps));
            worst = Math.max(worst, m.seriesCount());
        }
        check("count never rose above 104 under load", worst, 104);
        check("...and rests at 104", m.seriesCount(), 104);

        // A group collapsing near empty: the midpoint estimate reads ~112.
        PackMap c = new PackMap();
        for (int i = 0; i < 5; i++) c.add(syntheticReading(104, 77, 0.5));
        Reading knee = new Reading(0);
        knee.values.put("pack_v", (103 * 3250 + 2800) / 1000.0);
        knee.values.put("cell_min_mv", 2800.0);
        knee.values.put("cell_max_mv", 3250.0);
        knee.values.put("cell_min_idx", 77.0);
        knee.values.put("cell_max_idx", 4.0);
        knee.values.put("current_a", 2.0);
        knee.computeDerived();
        check("collapsing-group sample implies >110 series",
                knee.impliedSeries > 110, true);
        c.add(knee);
        check("...but does not grow the grid", c.seriesCount(), 104);
        check("...and the sample is still counted", c.samples(), 6);

        // An unseeded 96-group pack first seen under load must still read 96
        // from the first sample, and stay 96 once a balanced sample votes.
        PackMap t = new PackMap();
        for (double amps : sweep(80, 110, 10)) t.add(syntheticReading(96, 40, amps));
        check("96-group pack reads 96 before any balanced sample", t.seriesCount(), 96);
        t.add(syntheticReading(96, 40, 1.0));
        check("...and 96 after one", t.seriesCount(), 96);
    }

    /**
     * The findings a drive produced, named and ranked.
     *
     * The map coloured squares and the summary listed pack-level numbers, but
     * nothing said WHICH groups - with two suspects on screen the only verdict
     * sentence was the reassuring null case. And the dashboard's "weakest" tally
     * counted raw minima, which on a gently driven pack names the low-charge
     * floor group the map itself calls BALANCE.
     */
    private static void findings() {
        System.out.println("\n=== findings are named and ranked ===");
        PackMap m = syntheticPack(104, 77, 12, sweep(-70, 110, 200));
        List<PackMap.Snapshot> n = m.notable();
        check("resistive group is the first finding", n.isEmpty() ? -1 : n.get(0).index, 77);
        check("...as a SUSPECT", n.get(0).state, PackMap.State.SUSPECT);
        boolean plainListed = false;
        for (PackMap.Snapshot s : n) {
            if (s.state == PackMap.State.HEALTHY || s.state == PackMap.State.UNSEEN) {
                plainListed = true;
            }
        }
        check("healthy and unseen groups are not findings", plainListed, false);
        check("weakest under load is the resistive group", m.loadLeader(), 77);
        check("weakest at rest is the low-charge group", m.restLeader(), 12);
        String t = m.leadersText();
        check("leaders line names both bands",
                t.contains("under load: group 77") && t.contains("at rest: group 12"), true);
        check("nothing to say on an empty map", new PackMap().leadersText(), "");
        PackMap.Stats s = m.stats();
        check("stats carry the load leader", s.loadLeader == null ? -1 : s.loadLeader.index, 77);
        check("stats carry the rest leader", s.restLeader == null ? -1 : s.restLeader.index, 12);
    }

    /**
     * SOC context: how low a drive went, and who held the rest floor per band.
     *
     * The fault this app exists for lives below 14% SOC and no log has been
     * there yet; nothing said so. On an LFP pack rest voltage says little about
     * charge in the flat middle, so the floor group per SOC band is the only
     * capacity signal the min/max indices carry.
     */
    private static void socContext() {
        System.out.println("\n=== SOC context ===");
        PackMap m = new PackMap();
        check("no SOC yet", Double.isNaN(m.lowestSoc()), true);
        // At rest, group 1 is the floor above 90% and group 35 below it.
        for (int i = 0; i < 15; i++) m.add(socReading(95.0 - i * 0.1, 1, 0.5));
        for (int i = 0; i < 15; i++) m.add(socReading(85.0 - i * 0.1, 35, 0.5));
        for (int i = 0; i < 15; i++) m.add(socReading(84.0, 77, 60.0));   // load: not a rest floor
        check("lowest SOC tracked", Math.round(m.lowestSoc() * 10) / 10.0, 83.6);
        List<PackMap.SocBand> bands = m.restFloorBySoc();
        check("two rest bands", bands.size(), 2);
        check("highest band first", bands.get(0).lo, 90);
        check("90s floor is group 1", bands.get(0).leader, 1);
        check("80s floor is group 35", bands.get(1).leader, 35);
        check("load samples do not count as rest floor", bands.get(1).samples, 15);
        check("leader share is a percentage", bands.get(0).leaderPct, 100);
        // A reading without SOC still feeds the map; it just carries no context.
        PackMap plain = new PackMap();
        plain.add(packMapReading());
        check("no-SOC reading is still sampled", plain.samples(), 1);
        check("...and reports no SOC", Double.isNaN(plain.lowestSoc()), true);
    }

    private static Reading socReading(double soc, int minIdx, double amps) {
        Reading r = new Reading(0);
        r.values.put("soc_pct", soc);
        r.values.put("pack_v", 346.0);
        r.values.put("cell_min_mv", 3325.0);
        r.values.put("cell_max_mv", 3335.0);
        r.values.put("cell_min_idx", (double) minIdx);
        r.values.put("cell_max_idx", 60.0);
        r.values.put("current_a", amps);
        r.computeDerived();
        return r;
    }

    /** The map in words, for the share sheet. */
    private static void packReport() {
        System.out.println("\n=== findings report ===");
        // 600 steps, so the low-charge group collects the 20 rest minima BALANCE
        // requires; at 200 it has 11 and is (correctly) not a finding.
        PackMap m = syntheticPack(104, 77, 12, sweep(-70, 110, 600));
        String t = PackReport.render("test log", "MAT612345TEST0000", m);
        check("report names the vehicle", t.contains("MAT612345TEST0000"), true);
        check("report names the suspect with its excess, percentage first",
                t.contains("over the pack's average group (+0.30 mOhm)"), true);
        int pct = m.snapshot(77).excessPct;
        check("excess as a share of the pack's own group: 0.30 over 0.70 is ~43%",
                pct >= 41 && pct <= 45, true);
        check("...and the report carries it", t.contains("SUSPECT: +" + pct + "%"), true);
        check("an unmeasured group has no percentage", m.snapshot(50).excessPct, 0);
        check("report names the low-charge group", t.contains("Group 12 - LOW CHARGE"), true);
        check("report states its method", t.contains("Read-only"), true);
        check("empty map says so",
                PackReport.render("x", "", new PackMap()).contains("No samples"), true);
        PackMap s = new PackMap();
        for (int i = 0; i < 12; i++) s.add(socReading(68.0 + i * 0.1, 35, 0.5));
        check("report says how low the log went",
                PackReport.socLines(s).contains("Lowest SOC seen: 68%"), true);
        check("...and that the bottom has not been reached",
                PackReport.socLines(s).contains("near empty"), true);
        check("no SOC, no SOC lines", PackReport.socLines(m), "");
    }

    /**
     * What a list row can say about a log without replaying it: when, how long,
     * how far the SOC moved, how many rows. Log rows used to be a filename and
     * a byte count.
     */
    private static void logSummary() throws Exception {
        System.out.println("\n=== log summary ===");
        java.io.File f = java.io.File.createTempFile("bmssum", ".csv");
        f.deleteOnExit();
        java.io.PrintWriter w = new java.io.PrintWriter(f, "UTF-8");
        w.println("epoch_ms,timestamp,soc_pct,pack_v,app_ver,vin");
        w.println("1787754344265,2026-08-26 19:55:44,76,345.80,3.15,MAT612345TEST0000");
        w.println("1787756344265,2026-08-26 20:29:04,72.10,344.00,3.15,MAT612345TEST0000");
        w.println("1787758365839,2026-08-26 21:02:45,68.20,341.90,3.15,MAT612345TEST0000");
        w.close();
        LogSummary s = LogSummary.read(f);
        check("rows counted", s.rows, 3);
        check("start stamp", s.startMs, 1787754344265L);
        check("end stamp", s.endMs, 1787758365839L);
        check("SOC start", s.socStart, 76.0);
        check("SOC end", s.socEnd, 68.2);
        check("version", s.appVersion, "3.15");
        check("VIN", s.vin, "MAT612345TEST0000");
        // LogReader reads the VIN even when the file lacks the map's columns, so
        // the history screen can still say which car an unusable log came from.
        check("LogReader reads the VIN too", LogReader.read(f).vin, "MAT612345TEST0000");
        check("a log without a vin column reads as none",
                LogReader.read(java.io.File.createTempFile("novin", ".csv")).vin, "");
        check("described", s.describe(), "67 min · SOC 76.0 → 68.2% · 3 rows");
        java.io.File empty = java.io.File.createTempFile("bmsempty", ".csv");
        empty.deleteOnExit();
        new java.io.PrintWriter(empty, "UTF-8").close();
        check("empty file reads as zero rows", LogSummary.read(empty).rows, 0);
        check("...and describes itself without crashing",
                LogSummary.read(empty).describe(), "0 rows");
    }

    /**
     * The same group, drive after drive.
     *
     * Three independent drives naming the same group at the same excess is the
     * strongest evidence this app can produce, and it lived in three separate
     * screens. Verdicts are tallied per drive, not fits merged: each drive
     * pinned its own baseline, so the sums are not comparable across files.
     */
    private static void packHistory() {
        System.out.println("\n=== history across drives ===");
        List<PackMap> drives = new ArrayList<>();
        drives.add(syntheticPack(104, 77, 12, sweep(-70, 110, 600)));   // 77 SUSPECT, 12 BALANCE
        drives.add(syntheticPack(104, 77, 12, sweep(-60, 100, 450)));   // 77 SUSPECT, 12 BALANCE
        PackMap third = syntheticPack(104, 77, 12, sweep(10, 110, 300));   // 77 WATCH only...
        restStop(third, 12);                                                // ...once rested
        drives.add(third);
        PackHistory.Result h = PackHistory.aggregate(drives);
        check("three drives", h.drives, 3);
        PackHistory.GroupHistory g77 = h.groups[77];
        check("77 seen in every drive", g77.drives, 3);
        check("77 suspect twice", g77.suspect, 2);
        check("77 watch once", g77.watch, 1);
        check("77 displays as SUSPECT", g77.display(), PackMap.State.SUSPECT);
        check("excess range recorded", g77.excessLo > 0.25 && g77.excessHi < 0.35, true);
        check("77 is the first finding", h.notable().get(0).index, 77);
        check("low-charge group displays BALANCE", h.groups[12].display(), PackMap.State.BALANCE);
        check("never-named group is UNSEEN", h.groups[50].display(), PackMap.State.UNSEEN);
        check("grid sized to the pack", h.grid().size(), 104);
        check("sublabel counts agreeing drives", h.sublabels()[76], "2/3");
        String txt = h.render("MAT612345TEST0000");
        check("report says suspect in 2 of 3",
                txt.contains("Group 77 - SUSPECT in 2 of 3 drives"), true);
        check("report carries the excess range, percentage first",
                txt.matches("(?s).*\\+\\d+% to \\+\\d+% over the pack's average group, "
                        + "\\d\\.\\d\\d to \\d\\.\\d\\d mOhm.*"), true);
        check("percentage range is the suspect drives'", g77.pctLo >= 40 && g77.pctHi <= 46, true);
        check("empty history is honest", PackHistory.aggregate(new ArrayList<PackMap>()).drives, 0);
        check("...and has no findings",
                PackHistory.aggregate(new ArrayList<PackMap>()).notable().isEmpty(), true);
    }

    /** Every field is in every CSV - the selector is gone - and the map's six among them. */
    private static void csvContract() {
        System.out.println("\n=== CSV carries every field ===");
        String header = CsvFormat.header(BmsFields.ALL, new Prefs());
        for (BmsFields.Field f : BmsFields.ALL) {
            check("header has " + f.key, header.contains("," + f.key + ","), true);
        }
        for (String k : PackMap.REQUIRED_KEYS) {
            check("map role " + k + " is a logged field", header.contains("," + k + ","), true);
        }
        check("a fresh reading carries nothing", new Reading(0).carried.isEmpty(), true);
    }

    /**
     * Prioritised polling leaves SOC blank on four rows in five. The reader
     * carries the last value forward, so every sample still lands in a SOC band
     * and the lowest SOC is still known.
     */
    private static void carriedSoc() throws Exception {
        System.out.println("\n=== SOC carried across sparse rows ===");
        java.io.File f = java.io.File.createTempFile("bmssparse", ".csv");
        f.deleteOnExit();
        java.io.PrintWriter w = new java.io.PrintWriter(f, "UTF-8");
        w.println("epoch_ms,timestamp,soc_pct,pack_v,current_a,cell_min_mv,cell_min_idx,"
                + "cell_max_mv,cell_max_idx,app_ver");
        for (int i = 0; i < 15; i++) {
            w.printf(java.util.Locale.US,
                    "%d,2026-08-26 10:00:%02d,%s,346.0,0.5,3325,35,3335,60,3.18%n",
                    1787630000000L + i * 500L, i % 60, i == 0 ? "76" : "");
        }
        w.close();
        LogReader.Result r = LogReader.read(f);
        check("all rows sampled", r.rows, 15);
        check("SOC carried forward", Math.round(r.map.lowestSoc()), 76L);
        check("rest floor found in the carried band",
                r.map.restFloorBySoc().isEmpty() ? -1 : r.map.restFloorBySoc().get(0).leader, 35);
    }

    private static Reading readingAt(double minMv, double maxMv, double minIdx,
                                     double amps, Double aux) {
        Reading r = reading(minMv, maxMv, minIdx);
        r.values.put("current_a", amps);
        if (aux != null) r.values.put("aux_12v_v", aux);
        return r;
    }

    /**
     * The knee: the weakest cell falling faster than load explains. The fault
     * this app exists for is an LFP group running out before the rest, and it
     * shows as tens of millivolts lost in seconds at steady current, long
     * before the fixed floor. A sag under a throttle press is Ohm's law, not a
     * knee, and must not fire.
     */
    private static void kneeAlert() {
        System.out.println("\n=== falling weakest cell ===");
        Prefs prefs = new Prefs();
        FakeBeeper beeper = new FakeBeeper();
        long[] now = {1_000_000L};
        Alerter a = new Alerter(beeper, () -> now[0]);
        // A minute of steady cruising: flat minimum, nothing to say.
        for (int i = 0; i < 60; i++) {
            now[0] += 1000;
            a.evaluate(readingAt(3300, 3320, 77, 20, null), prefs);
        }
        check("flat minimum is quiet", a.activeReason(), null);
        // The knee: 12 mV a second at the same current, the whole pack sagging
        // with it so the spread alert does not fire first.
        String fired = null;
        int firedAt = -1;
        for (int i = 1; i <= 5; i++) {
            now[0] += 1000;
            String f = a.evaluate(readingAt(3300 - 12 * i, 3320 - 12 * i, 77, 20, null), prefs);
            if (f != null && fired == null) {
                fired = f;
                firedAt = i;
            }
        }
        check("a 40 mV fall at steady current fires", fired != null && fired.contains("falling"), true);
        check("...naming the group", fired != null && fired.contains("group 77"), true);
        check("...after two confirming samples, not one", firedAt, 5);
        check("...and beeps", beeper.beeps, 1);

        // The same fall under a rising load is not a knee.
        a = new Alerter(beeper = new FakeBeeper(), () -> now[0]);
        for (int i = 0; i < 30; i++) {
            now[0] += 1000;
            a.evaluate(readingAt(3300, 3320, 77, 20, null), prefs);
        }
        for (int i = 1; i <= 5; i++) {
            now[0] += 1000;
            a.evaluate(readingAt(3300 - 12 * i, 3320 - 12 * i, 77, 20 + 30 * i, null), prefs);
        }
        check("a fall under rising load is not a knee", a.activeReason(), null);
        check("...and does not beep", beeper.beeps, 0);

        // A reading without current cannot be judged and must not crash.
        check("no current, no knee verdict",
                a.evaluate(reading(3200, 3220, 77.0), prefs), null);
    }

    /** The 12 V rail: low while the pack is working is a fault; asleep it is not. */
    private static void auxAlert() {
        System.out.println("\n=== 12 V rail ===");
        Prefs prefs = new Prefs();
        long[] now = {1_000_000L};
        FakeBeeper beeper = new FakeBeeper();
        Alerter a = new Alerter(beeper, () -> now[0]);
        check("healthy rail is quiet",
                a.evaluate(readingAt(3300, 3320, 7, 30, 13.7), prefs), null);
        String f = a.evaluate(readingAt(3300, 3320, 7, 30, 11.8), prefs);
        check("low rail while driving fires", f != null && f.contains("12 V"), true);
        check("...and beeps", beeper.beeps, 1);
        a = new Alerter(new FakeBeeper(), () -> now[0]);
        check("low rail with the pack idle is not an alert",
                a.evaluate(readingAt(3300, 3320, 7, 0.2, 11.8), prefs), null);
        check("low rail while charging fires too",
                a.evaluate(readingAt(3300, 3320, 7, -40, 11.8), prefs) != null, true);
        check("no rail reading, no alert",
                new Alerter(new FakeBeeper(), () -> now[0])
                        .evaluate(readingAt(3300, 3320, 7, 30, null), prefs), null);
    }

    /**
     * Verdicts are announced once each and again only on escalation. A watch
     * group is news the first time and noise the tenth; low charge is not a
     * fault and is never announced.
     */
    private static void verdictAlerts() {
        System.out.println("\n=== verdicts announced once ===");
        Prefs prefs = new Prefs();
        FakeBeeper beeper = new FakeBeeper();
        Alerter a = new Alerter(beeper, () -> 1_000_000L);
        PackMap watch = syntheticPack(104, 77, 12, sweep(10, 110, 300));      // 77 WATCH...
        restStop(watch, 12);                                                    // ...once rested
        String first = a.verdictAlert(watch.notable(), prefs);
        check("a new watch group is announced",
                first != null && first.contains("Group 77") && first.contains("watch"), true);
        check("...with a beep", beeper.beeps, 1);
        check("the same verdict is not announced twice",
                a.verdictAlert(watch.notable(), prefs), null);
        PackMap suspect = syntheticPack(104, 77, 12, sweep(-70, 110, 600));  // 77 SUSPECT, 12 BALANCE
        String esc = a.verdictAlert(suspect.notable(), prefs);
        check("escalation to weak module is announced",
                esc != null && esc.contains("Group 77") && esc.contains("weak module"), true);
        check("...once", a.verdictAlert(suspect.notable(), prefs), null);
        check("low charge is never announced",
                esc != null && !esc.contains("Group 12"), true);
        a.reset();
        check("a link reset keeps the announcements",
                a.verdictAlert(suspect.notable(), prefs), null);
        a.forgetVerdicts();
        check("a new pack starts the announcements over",
                a.verdictAlert(suspect.notable(), prefs) != null, true);
        check("nothing notable, nothing announced",
                a.verdictAlert(new PackMap().notable(), prefs), null);
        prefs.alerts = false;
        FakeBeeper quiet = new FakeBeeper();
        Alerter b = new Alerter(quiet, () -> 1_000_000L);
        check("alerts off still announces on screen",
                b.verdictAlert(suspect.notable(), prefs) != null, true);
        check("...without a beep", quiet.beeps, 0);
    }

    /** The current scale from a charger's displayed power. */
    private static void calibration() {
        System.out.println("\n=== current scale from a charger ===");
        // 30 kW into 346.2 V is 86.7 A; the BMS counted 867 below zero.
        CurrentCalibration.Result c = CurrentCalibration.fromCharger(
                30.0, 346.2, String.format("%04X", 32000 - 867), 32000);
        check("no error", c.error, null);
        check("scale recovers 0.1 A per count", Math.round(c.scale * 1000) / 1000.0, 0.1);
        check("amps derived from power", Math.round(c.amps), 87L);
        check("a normal-convention result carries no caution", c.note, null);
        // A charging car counting UPWARD is a BMS with the opposite sign
        // convention (or a wrong zero): the scale comes back negative, with a
        // caution, rather than refused - the user said the car is charging.
        CurrentCalibration.Result up = CurrentCalibration.fromCharger(
                30.0, 346.2, String.format("%04X", 32000 + 867), 32000);
        check("upward counting gives a negative scale",
                Math.round(up.scale * 1000) / 1000.0, -0.1);
        check("...with a caution naming the zero point", up.note != null && up.note.contains("zero point"), true);
        check("a trickle is refused",
                CurrentCalibration.fromCharger(30.0, 346.2, "7CF6", 32000).error != null, true);
        check("no reading is refused",
                CurrentCalibration.fromCharger(30.0, null, null, 32000).error != null, true);
        check("zero power is refused",
                CurrentCalibration.fromCharger(0, 346.2, "799D", 32000).error != null, true);
        // The zero from a parked car is simply the raw count as it stands.
        check("zero from a parked car", CurrentCalibration.zeroFromParked("7D05"), 32005);
        check("zero needs a reading", CurrentCalibration.zeroFromParked(null), null);
    }

    /** Two-speed polling's one decision, tabled. */
    private static void pollPlan() {
        System.out.println("\n=== two-speed polling: judging a fast cycle ===");
        check("cells decoded -> proceed",
                PollPlan.judgeFast(true, false, true), PollPlan.Fast.PROCEED);
        check("no cells ever, ECU answered -> full polls",
                PollPlan.judgeFast(false, false, false), PollPlan.Fast.DROP_TO_SINGLE);
        check("no cells ever, silence -> full polls, judged there",
                PollPlan.judgeFast(false, true, false), PollPlan.Fast.DROP_TO_SINGLE);
        check("cells used to decode, ECU answered -> transient, skip",
                PollPlan.judgeFast(false, false, true), PollPlan.Fast.SKIP);
        check("cells used to decode, silence -> empty-poll rule",
                PollPlan.judgeFast(false, true, true), PollPlan.Fast.EMPTY);
    }

    /**
     * Ohm's law is not a knee. The ±20 A window alone admits 20 A x R of sag -
     * quiet at 0.7 mOhm a group, a false alarm at the 2 mOhm of a smaller-cell
     * or cold pack on an ordinary regen-to-throttle transition. Each sample is
     * compared as V + I x R instead.
     */
    private static void kneeIrCorrection() {
        System.out.println("\n=== knee test takes load out first ===");
        Prefs prefs = new Prefs();
        long[] now = {1_000_000L};
        // A 2 mOhm-per-group pack: min cell sags 2 mV per amp.
        FakeBeeper beeper = new FakeBeeper();
        Alerter a = new Alerter(beeper, () -> now[0]);
        for (int i = 0; i < 20; i++) {
            now[0] += 1000;
            a.evaluate(readingAt(3300 + 80, 3320 + 80, 77, -40, null), prefs);   // regen
        }
        for (int amps = -40; amps <= 40; amps += 10) {                             // throttle ramp
            now[0] += 1000;
            a.evaluate(readingAt(3300 - 2 * amps, 3320 - 2 * amps, 77, amps, null), prefs);
        }
        check("a throttle ramp on a 2 mOhm pack is not a knee (default R)",
                a.activeReason(), null);
        check("...and does not beep", beeper.beeps, 0);
        // With the pack's own fit fed in, a real fall at steady current still fires.
        a.setGroupMilliOhm(2.0);
        for (int i = 0; i < 10; i++) {
            now[0] += 1000;
            a.evaluate(readingAt(3300 - 80, 3320 - 80, 77, 40, null), prefs);
        }
        String fired = null;
        for (int i = 1; i <= 5; i++) {
            now[0] += 1000;
            String f = a.evaluate(readingAt(3300 - 80 - 12 * i, 3320 - 80 - 12 * i, 77, 40, null), prefs);
            if (f != null && fired == null) fired = f;
        }
        check("a real fall at steady current still fires", fired != null && fired.contains("falling"), true);
    }

    /**
     * A permanently imbalanced pack must still get a stable count and a pinned
     * baseline. The balanced-only vote left such a pack - one group 30 mV low
     * on charge, exactly the near-empty pack this app asks the user to record -
     * with no votes at all, so the count followed the load and a true 0.30 mOhm
     * group fitted 0.25. Resting samples vote too now.
     */
    private static void imbalancedPack() {
        System.out.println("\n=== an imbalanced pack still pins its baseline ===");
        PackMap m = new PackMap();
        int worst = 0;
        for (double amps : sweep(-70, 110, 600)) {
            m.add(imbalancedReading(104, 77, 12, -30, amps));
            worst = Math.max(worst, m.seriesCount());
        }
        check("count settled from resting samples", m.countSettled(), true);
        check("count never left 104", worst, 104);
        double ex = m.snapshot(77).excessMilliOhm;
        check("fitted excess within 0.02 of the true 0.30",
                Math.abs(ex - 0.30) < 0.02, true);
        // Never balanced, never at rest: nothing can vote, and the count must
        // then at least be honest that it is unsettled.
        PackMap loaded = new PackMap();
        for (double amps : sweep(30, 110, 100)) loaded.add(imbalancedReading(104, 77, 12, -30, amps));
        check("a loaded-only imbalanced log is not 'settled'", loaded.countSettled(), false);
        // A collapsing group at rest must not vote its 110+ series in.
        PackMap knee = new PackMap();
        for (int i = 0; i < 25; i++) knee.add(imbalancedReading(104, 77, 12, -30, 0.5));
        int before = knee.seriesCount();
        for (int i = 0; i < 40; i++) knee.add(imbalancedReading(104, 77, 12, -450, 0.5));
        check("a collapsing group at rest does not move the count", knee.seriesCount(), before);
    }

    private static Reading imbalancedReading(int n, int resistive, int lowCharge,
                                             double lowMv, double amps) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE, packMv = 0;
        int loI = 1, hiI = 1;
        for (int g = 1; g <= n; g++) {
            double ocv = 3330.0 + (g == lowCharge ? lowMv : 0);
            double r = (g == resistive) ? 1.00 : 0.70;
            double v = ocv - amps * r;
            packMv += v;
            if (v < lo) { lo = v; loI = g; }
            if (v > hi) { hi = v; hiI = g; }
        }
        Reading r = new Reading(0);
        r.values.put("pack_v", packMv / 1000.0);
        r.values.put("cell_min_mv", lo);
        r.values.put("cell_max_mv", hi);
        r.values.put("cell_min_idx", (double) loI);
        r.values.put("cell_max_idx", (double) hiI);
        r.values.put("current_a", amps);
        r.computeDerived();
        return r;
    }

    /**
     * Two suspects at once: ranked by how often each limits the pack under
     * load, and announced on consecutive polls rather than in one toast. Fed
     * through the primitive add() with fabricated extremes, because a
     * deterministic synthetic pack cannot put two groups at both ends.
     */
    private static void twoSuspects() {
        System.out.println("\n=== two suspects ===");
        PackMap m = new PackMap();
        for (int i = 0; i < 40; i++) {
            // Under load 77 is the floor three times as often as 18.
            m.add(346.0, 3270, 3335, i % 4 == 0 ? 18 : 77, 50, 100);
            // Under regen each is the ceiling in turn.
            m.add(346.0, 3325, 3351, 50, i % 2 == 0 ? 77 : 18, -70);
        }
        List<PackMap.Snapshot> n = m.notable();
        check("both groups are suspects",
                n.size() >= 2 && n.get(0).state == PackMap.State.SUSPECT
                        && n.get(1).state == PackMap.State.SUSPECT, true);
        check("the group that limits the pack most often ranks first",
                n.get(0).index, 77);
        check("...ahead of the rarer one", n.get(1).index, 18);

        FakeBeeper beeper = new FakeBeeper();
        Alerter a = new Alerter(beeper, () -> 1_000_000L);
        Prefs prefs = new Prefs();
        String first = a.verdictAlert(n, prefs);
        String second = a.verdictAlert(n, prefs);
        check("first poll announces the leading suspect",
                first != null && first.contains("Group 77"), true);
        check("next poll announces the other",
                second != null && second.contains("Group 18"), true);
        check("then nothing more", a.verdictAlert(n, prefs), null);
        check("one beep per announcement", beeper.beeps, 2);

        // reset() clears the knee window along with the rest of the link state.
        long[] now = {1_000_000L};
        Alerter k = new Alerter(new FakeBeeper(), () -> now[0]);
        for (int i = 0; i < 30; i++) {
            now[0] += 1000;
            k.evaluate(readingAt(3300, 3320, 77, 20, null), prefs);
        }
        now[0] += 1000;
        k.evaluate(readingAt(3255, 3275, 77, 20, null), prefs);   // one confirming sample
        k.reset();
        now[0] += 1000;
        check("a reset forgets a half-confirmed fall",
                k.evaluate(readingAt(3250, 3270, 77, 20, null), prefs), null);
    }

    /**
     * The CSV to the byte: every raw column names its DID, the provenance block
     * comes last, and a role the service carried from an earlier poll is blank
     * in both its value and its raw column rather than written as fresh.
     */
    private static void csvRows() {
        System.out.println("\n=== CSV rows ===");
        Prefs prefs = new Prefs();
        List<BmsFields.Field> logged = BmsFields.ALL;
        String header = CsvFormat.header(logged, prefs);
        String[] cols = header.split(",", -1);
        check("header names the DID of every raw column",
                header.contains("raw_pack_v_3400") && header.contains("raw_cell_min_idx_341A"), true);
        check("header ends with the provenance block",
                header.endsWith(",app_ver,bms_id,cur_scale,cur_zero,vin"), true);
        Reading r = new Reading(1787754344265L);
        r.values.put("pack_v", 346.2);
        r.raw.put("pack_v", "0D86");
        r.values.put("soc_pct", 76.0);
        r.raw.put("soc_pct", "02F8");
        r.carried.add("soc_pct");
        r.computeDerived();
        r.curScale = 0.1f;
        r.curZero = 32000;
        String[] f = CsvFormat.row(r, logged, "2026-08-26 19:55:44", "3.18", "785",
                "MAT612345TEST0000").split(",", -1);
        check("row has one field per column", f.length, cols.length);
        check("measured value written", f[colIndex(cols, "pack_v")], "346.20");
        check("measured raw written", f[colIndex(cols, "raw_pack_v_3400")], "0D86");
        check("carried value blank", f[colIndex(cols, "soc_pct")], "");
        check("carried raw blank", f[colIndex(cols, "raw_soc_pct_3402")], "");
        check("scale written with a dot whatever the locale",
                f[colIndex(cols, "cur_scale")], "0.1000");
        check("vin last", f[f.length - 1], "MAT612345TEST0000");
        check("whole numbers carry no decimals", CsvFormat.trim(3300.0), "3300");
        // The calibration is the READING's, not the file's: a file that straddles
        // a Settings change stays true row by row.
        Reading later = new Reading(1787754345265L);
        later.curScale = 0.0973f;
        later.curZero = 32768;
        String[] f2 = CsvFormat.row(later, logged, "2026-08-26 19:55:45", "3.18", "785",
                "MAT612345TEST0000").split(",", -1);
        check("next row carries its own scale", f2[colIndex(cols, "cur_scale")], "0.0973");
        check("...and its own zero", f2[colIndex(cols, "cur_zero")], "32768");
    }

    private static int colIndex(String[] cols, String name) {
        for (int i = 0; i < cols.length; i++) if (cols[i].equals(name)) return i;
        return -1;
    }

    /** A stop at the lights: 25 resting samples with {@code floor} the lowest group. */
    private static void restStop(PackMap m, int floor) {
        for (int i = 0; i < 25; i++) m.add(346.0, 3318, 3330, floor, 60, 0.5);
    }

    /**
     * Before the first sample an unlearned pack draws nothing rather than a
     * guessed 104 squares; a seeded one draws its learned size. And the suspect
     * threshold follows the pack's own per-group resistance, so a smaller-cell
     * pack is not flooded with 5% "suspects".
     */
    private static void emptyGridAndThreshold() {
        System.out.println("\n=== empty grid, pack-relative threshold ===");
        check("no samples, no seed -> nothing to draw", new PackMap().grid().size(), 0);
        PackMap seeded = new PackMap();
        seeded.seed(96);
        check("no samples, seeded -> the learned size", seeded.grid().size(), 96);
        PackMap m = syntheticPack(104, 77, 12, sweep(-70, 110, 200));
        check("threshold floor on the Nexon-sized pack",
                Math.round(m.suspectThreshold() * 1000) / 1000.0, 0.105);
        check("default before any fit", new PackMap().groupMilliOhm(), PackMap.DEFAULT_GROUP_MILLIOHM);
        // A seeded count is not overturned by a single first vote.
        PackMap s = new PackMap();
        s.seed(104);
        Reading r = syntheticReading(104, 77, 0.5);
        r.impliedSeries = 104.6;
        s.add(r);
        check("one 104.6 vote does not move a learned 104", s.seriesCount(), 104);
    }

    /**
     * A wrong zero point shows as tens of amps from a stationary pack: steady
     * voltage, flat SOC, big current, for a whole window. Driving moves the
     * voltage; DC charging moves the SOC; a correctly-zeroed parked car shows
     * under an amp. None of those may fire it.
     */
    private static void zeroCheck() {
        System.out.println("\n=== a wrong zero point is noticed ===");
        // Parked Nexon decoded through 0x8000 instead of 32000: -75 A at rest.
        ZeroCheck z = new ZeroCheck();
        boolean early = false, fired = false;
        for (int s = 0; s <= 45; s++) {
            boolean f = z.feed(1_000_000L + s * 1000L, 346.2 + (s % 3) * 0.1, -75.1, 76.0);
            if (s < 30 && f) early = true;
            if (f) fired = true;
        }
        check("wrong zero on a parked car is noticed", fired, true);
        check("...but not before the window has filled", early, false);

        // The same car with the right zero: under an amp. Quiet.
        ZeroCheck ok = new ZeroCheck();
        boolean any = false;
        for (int s = 0; s <= 45; s++) any |= ok.feed(1_000_000L + s * 1000L, 346.2, 0.5, 76.0);
        check("correct zero on a parked car is quiet", any, false);

        // Driving: the voltage moves with the throttle. Quiet, whatever the current.
        ZeroCheck drive = new ZeroCheck();
        any = false;
        for (int s = 0; s <= 45; s++) {
            any |= drive.feed(1_000_000L + s * 1000L, 340 + 4 * Math.sin(s / 3.0), 60.0, 75.0);
        }
        check("driving is quiet", any, false);

        // DC fast charging: steady voltage, big current - but the SOC climbs.
        ZeroCheck charge = new ZeroCheck();
        any = false;
        for (int s = 0; s <= 45; s++) {
            any |= charge.feed(1_000_000L + s * 1000L, 350.0 + s * 0.005, -100.0, 60.0 + s * 0.02);
        }
        check("DC charging is quiet", any, false);

        // No SOC: cannot tell charging from parked, so it must not guess.
        ZeroCheck blind = new ZeroCheck();
        any = false;
        for (int s = 0; s <= 45; s++) any |= blind.feed(1_000_000L + s * 1000L, 346.2, -75.1, null);
        check("without SOC it stays quiet", any, false);
        z.reset();
        check("a reset starts the evidence over",
                z.feed(2_000_000L, 346.2, -75.1, 76.0), false);
    }

    /** One live-shaped reading of an n-group pack with one 0.30 mOhm outlier. */
    private static Reading syntheticReading(int n, int resistive, double amps) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE, packMv = 0;
        int loI = 1, hiI = 1;
        for (int g = 1; g <= n; g++) {
            double r = (g == resistive) ? 1.00 : 0.70;
            double v = 3330.0 - amps * r;
            packMv += v;
            if (v < lo) { lo = v; loI = g; }
            if (v > hi) { hi = v; hiI = g; }
        }
        Reading r = new Reading(0);
        r.values.put("pack_v", packMv / 1000.0);
        r.values.put("cell_min_mv", lo);
        r.values.put("cell_max_mv", hi);
        r.values.put("cell_min_idx", (double) loI);
        r.values.put("cell_max_idx", (double) hiI);
        r.values.put("current_a", amps);
        r.computeDerived();
        return r;
    }

    /**
     * Broadcast discovery: a functional probe's reply text names every ECU on
     * the bus, and each response id maps back to a typable request id.
     */
    private static void discovery() {
        System.out.println("\n=== broadcast ECU discovery ===");
        // Three ECUs answer one functional probe: a session reply, an NRC, and
        // a TesterPresent acknowledgement - all single frames, all count.
        String text = "7E80462F18601\n78D037F2211\n7C6027E00";
        check("all responding ids heard", UdsCodec.respondingIds(text).size(), 3);
        check("Tata pair maps back", UdsCodec.requestIdFor("78D"), "785");
        check("ISO pair maps back", UdsCodec.requestIdFor("7E8"), "7E0");
        check("below-range response rejected", UdsCodec.requestIdFor("005"), null);
        check("junk rejected", UdsCodec.requestIdFor("XYZ"), null);
        check("bare ATCRA (open filter) is allowed",
                CommandGuard.isAllowed("ATCRA"), true);
        check("ATCRA with an id still allowed",
                CommandGuard.isAllowed("ATCRA 78D"), true);
        check("ATMA is still refused", CommandGuard.isAllowed("ATMA"), false);
    }

    /** A Reading carrying exactly the pack-map-required roles, nothing else. */
    private static Reading packMapReading() {
        Reading r = new Reading(0);
        r.values.put("pack_v", 350.0);
        r.values.put("cell_min_mv", 3350.0);
        r.values.put("cell_max_mv", 3370.0);
        r.values.put("cell_min_idx", 77.0);
        r.values.put("cell_max_idx", 12.0);
        r.values.put("current_a", 30.0);
        r.computeDerived();
        return r;
    }

    // --------------------------------------------------------- reading logs

    /**
     * A replayed log must derive the pack's group count, not assume 104.
     *
     * PackMap has two add() overloads and only the Reading one reads
     * impliedSeries. LogReader called the primitive one, so every replayed log
     * kept seriesCount at the hardcoded 104 whatever car wrote it: a 96-group
     * Tiago reported "N of 104 groups seen", drew 8 phantom squares, and divided
     * the deviation baseline by 104 - so the SAME drive fitted a different
     * resistance replayed than it did live. This is exactly the generalisation
     * case the app exists for.
     */
    private static void replayDerivesGroupCount() throws Exception {
        System.out.println("\n=== a replayed log derives the group count ===");
        final int series = 96, resistive = 40;
        java.io.File f = java.io.File.createTempFile("bms96", ".csv");
        f.deleteOnExit();
        java.io.PrintWriter w = new java.io.PrintWriter(f, "UTF-8");
        w.println("epoch_ms,timestamp,pack_v,current_a,cell_min_mv,cell_min_idx,"
                + "cell_max_mv,cell_max_idx,raw_cell_min_idx_341A,raw_cell_max_idx_3419,"
                + "app_ver");

        // The same pack, fed twice: once through the file, once live.
        PackMap live = new PackMap();
        int rows = 0;
        for (int i = 0; i < 120; i++) {
            double amps = -70 + i * 1.5;
            double packMv = 0, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            int loI = 1, hiI = 1;
            for (int g = 1; g <= series; g++) {
                double r = (g == resistive) ? 1.00 : 0.70;
                double v = 3330.0 - amps * r;
                packMv += v;
                if (v < lo) { lo = v; loI = g; }
                if (v > hi) { hi = v; hiI = g; }
            }
            w.printf(java.util.Locale.US,
                    "%d,2026-08-25 10:00:%02d,%.2f,%.2f,%.0f,%d,%.0f,%d,%02X,%02X,3.7%n",
                    1787630000000L + i * 1000L, i % 60, packMv / 1000.0, amps,
                    lo, loI, hi, hiI, loI, hiI);

            Reading rd = new Reading(0);
            rd.values.put("pack_v", packMv / 1000.0);
            rd.values.put("cell_min_mv", lo);
            rd.values.put("cell_max_mv", hi);
            rd.values.put("cell_min_idx", (double) loI);
            rd.values.put("cell_max_idx", (double) hiI);
            rd.values.put("current_a", amps);
            rd.computeDerived();
            live.add(rd);
            rows++;
        }
        w.close();

        LogReader.Result r = LogReader.read(f);
        check("rows replayed", r.rows, rows);
        check("group count derived from the file, not assumed",
                r.map.seriesCount(), series);
        check("no phantom groups past the end", r.map.lastIndex(), series);
        check("live path agrees on the count", live.seriesCount(), series);
        // The whole point: replayed and live must be the same measurement.
        double replayed = r.map.snapshot(resistive).excessMilliOhm;
        double liveFit = live.snapshot(resistive).excessMilliOhm;
        // Not bit-identical, and should not be: the CSV stores pack volts to two
        // decimals and cell voltages as whole millivolts, exactly as CsvLogger
        // writes them. What must hold is that the remaining difference is that
        // quantisation and nothing else - it sits below the third decimal the
        // screen reports. The bug this test exists for is far larger: replaying
        // this same 96-group log through the primitive add() reports seriesCount
        // 104 and fits 0.35 mOhm against a true 0.30 - a 17% overstatement, on the
        // number the whole pack map exists to produce.
        check("replayed and live fit the same resistance",
                Math.abs(replayed - liveFit) < 1e-3, true);
        check("...and it is the true 0.30 mOhm",
                Math.round(replayed * 100) / 100.0, 0.3);
    }

    private static void logReader() throws Exception {
        System.out.println("\n=== reading a saved log ===");
        java.io.File f = java.io.File.createTempFile("bmslog", ".csv");
        f.deleteOnExit();
        // Written in the PRE-v3.3 layout: the role names are the wrong way round,
        // but each raw column carries the DID that actually produced it.
        java.io.PrintWriter w = new java.io.PrintWriter(f, "UTF-8");
        w.println("epoch_ms,timestamp,pack_v,current_a,cell_min_mv,cell_min_idx,"
                + "cell_max_mv,cell_max_idx,raw_cell_min_idx_3419,raw_cell_max_idx_341A,"
                + "app_ver");
        // cell_min_idx column holds 4 (really the MAX index on this vehicle) and
        // cell_max_idx holds 77 (really the MIN). A reader trusting the names
        // would attribute every low-voltage sample to group 4.
        for (int i = 0; i < 40; i++) {
            double amps = -60 + i * 4.0;
            w.printf(java.util.Locale.US,
                    "%d,2026-08-25 10:00:%02d,346.0,%.1f,3300,4,3340,77,04,4D,3.2%n",
                    1787630000000L + i * 1000L, i % 60, amps);
        }
        w.close();

        LogReader.Result r = LogReader.read(f);
        check("rows read", r.rows, 40);
        check("older layout detected", r.flipCorrected, true);
        check("app version carried through", r.appVersion, "3.2");
        // 77 was in the cell_max_idx COLUMN, but the DID says that column is the
        // minimum - so 77 must be counted as having held the minimum.
        check("index roles resolved by DID, not by column name",
                r.map.group(77).minCount, 40);
        check("...and the other index went to the maximum",
                r.map.group(4).maxCount, 40);
        check("weakest group is the one the DID says",
                r.map.weakestGroup(), 77);
    }

    // ------------------------------------------------------- adapter refusal

    private static void adapterReject() {
        System.out.println("\n=== adapter rejection ===");
        // reassemble() filters "?" as junk, so this is the only place a batched
        // request the ADAPTER refused can be told from an ECU that stayed silent.
        check("bare ? is an adapter rejection",
                UdsCodec.isAdapterReject("?\r\r>"), true);
        check("? on its own line is an adapter rejection",
                UdsCodec.isAdapterReject("78D0762340288\r?\r>"), true);
        check("a normal reply is not",
                UdsCodec.isAdapterReject("78D0762340288FF"), false);
        check("NO DATA is not an adapter rejection",
                UdsCodec.isAdapterReject("NO DATA"), false);
        check("null is not an adapter rejection",
                UdsCodec.isAdapterReject(null), false);
    }

    // ---------------------------------------- third review: pure decisions

    private static void requestIdBound() {
        System.out.println("\n=== request id bound and read framing ===");
        check("785 is a valid request id", CommandGuard.isRequestId("785"), true);
        check("7F7 is the last id whose reply fits 11 bits", CommandGuard.isRequestId("7F7"), true);
        check("7F8 would reply on 800", CommandGuard.isRequestId("7F8"), false);
        check("FFF rejected", CommandGuard.isRequestId("FFF"), false);
        check("two digits rejected", CommandGuard.isRequestId("85"), false);
        check("blank rejected", CommandGuard.isRequestId(""), false);
        check("null rejected", CommandGuard.isRequestId(null), false);
        check("non-hex rejected", CommandGuard.isRequestId("7G5"), false);
        check("lower case accepted", CommandGuard.isRequestId("7a5"), true);
        check("odd nibble read refused", CommandGuard.isAllowed("22F19"), false);
        check("half a DID refused", CommandGuard.isAllowed("22F19034"), false);
        check("three DIDs allowed", CommandGuard.isAllowed("22F19034023401"), true);
        check("four DIDs refused", CommandGuard.isAllowed("22F190340234013400"), false);
    }

    private static void negativeCurrentScale() {
        System.out.println("\n=== negative current scale ===");
        // 0x7CFB is five counts below the measured zero. On the Nexon that is
        // -0.5 A; a model whose counter runs the other way is calibrated with a
        // negative scale and the same raw bytes read +0.5 A.
        byte[] raw = UdsCodec.decode22("78D056234017CFB", "78D").data;
        check("Nexon sign", BmsFields.decode(BmsFields.byDid("3401"), raw, 0.1, 32000), -0.5);
        check("reversed model", BmsFields.decode(BmsFields.byDid("3401"), raw, -0.1, 32000), 0.5);
        check("a different zero point moves the reading",
                BmsFields.decode(BmsFields.byDid("3401"), raw, 0.1, 31990), 0.5);
        Reading r = new Reading(0L);
        check("a reading with nothing decoded is not usable", r.isUsable(), false);
        r.values.put("pack_v", 350.0);
        check("pack volts alone do not make a sample", r.isUsable(), false);
        r.values.put("soc_pct", 60.0);
        check("SOC makes a slow sample usable", r.isUsable(), true);
        Reading fast = new Reading(0L);
        fast.values.put("cell_min_mv", 3600.0);
        check("the weakest cell makes a fast sample usable", fast.isUsable(), true);
    }

    private static void vinFold() {
        System.out.println("\n=== history: where logs without a VIN belong ===");
        PackMap a = new PackMap(), b = new PackMap(), old1 = new PackMap(), old2 = new PackMap();
        PackMap noVinCar = new PackMap();
        java.util.Map<String, java.util.List<PackMap>> byVin = new java.util.LinkedHashMap<>();
        byVin.put("MAT1", maps(a));
        PackHistory.placePreVin(byVin, maps(old1, old2));
        check("one car: its older logs are its drives", byVin.get("MAT1").size(), 3);
        check("...and no unknown-vehicle entry appears", byVin.containsKey(""), false);

        byVin = new java.util.LinkedHashMap<>();
        byVin.put("MAT1", maps(a));
        byVin.put("MAT2", maps(b));
        PackHistory.placePreVin(byVin, maps(old1));
        check("two cars: an older log cannot be assigned", byVin.get("MAT1").size(), 1);
        check("...so it is its own entry", byVin.get("").size(), 1);

        // A car whose controller serves no VIN writes an empty vin column on
        // every row. That is a second car, not an older log.
        byVin = new java.util.LinkedHashMap<>();
        byVin.put("MAT1", maps(a));
        byVin.put("", maps(noVinCar));
        PackHistory.placePreVin(byVin, maps(old1));
        check("a VIN-less car keeps its own drives", byVin.get("").size(), 2);
        check("...and the VIN car is untouched", byVin.get("MAT1").size(), 1);

        byVin = new java.util.LinkedHashMap<>();
        PackHistory.placePreVin(byVin, maps(old1, old2));
        check("no VIN anywhere: one unknown-vehicle entry", byVin.size(), 1);
        check("...holding every drive", byVin.get("").size(), 2);

        byVin = new java.util.LinkedHashMap<>();
        byVin.put("MAT1", maps(a));
        PackHistory.placePreVin(byVin, new java.util.ArrayList<PackMap>());
        check("nothing to place changes nothing", byVin.get("MAT1").size(), 1);
    }

    private static java.util.List<PackMap> maps(PackMap... m) {
        return new java.util.ArrayList<>(java.util.Arrays.asList(m));
    }
}
