package com.tataev.bms;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sweeps a BMS's DID space on the vehicle itself, so a Tata EV whose parameter
 * map differs from the Nexon EV Max can still be worked out from the phone.
 *
 * This is the same technique used to discover the map in the first place: ask for
 * each DID and see which ones answer rather than returning NRC 0x31
 * (requestOutOfRange). Read-only - service 0x22 only.
 *
 * Each responder is sampled twice so a caller can tell a live signal from static
 * configuration, and the results are run through {@link #suggest} to propose
 * which DID plays which role.
 */
final class DidScanner {

    /** One DID that answered. */
    static final class Hit {
        final String did;
        final int length;
        final String firstHex;
        final String secondHex;

        /** Parsed once: first() is called from an O(n^3) hypothesis search. */
        private final int firstValue;

        Hit(String did, int length, String firstHex, String secondHex) {
            this.did = did;
            this.length = length;
            this.firstHex = firstHex;
            this.secondHex = secondHex;
            this.firstValue = valueOf(firstHex);
        }

        boolean changed() {
            return secondHex != null && !secondHex.equals(firstHex);
        }

        int valueOf(String hex) {
            try {
                return Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        int first() {
            return firstValue;
        }
    }

    interface Progress {
        /** @return false to abort the scan */
        boolean onProgress(int did, int hits, int percent);
    }

    /** NRC meaning "no such DID" - the pre-filter working correctly. */
    private static final int NRC_OUT_OF_RANGE = 0x31;
    /** NRC meaning the ECU rejected the multi-DID FORM of the request. */
    private static final int NRC_INVALID_LENGTH = 0x13;
    /** Undecodable probes tolerated before abandoning the batched pre-filter. */
    private static final int BATCH_PROBE_GIVEUP = 3;
    /**
     * Consecutive transport errors before the link is declared dead. Silence is
     * NOT counted - a healthy socket returning nothing is an absent DID - only
     * IOExceptions are, because those mean the socket itself failed.
     */
    static final int LINK_DEAD_LIMIT = 5;

    private final List<Hit> hits = new ArrayList<>();
    /** Cleared when this ECU turns out not to accept batched reads. */
    private boolean batchProbeUsable = true;
    private int unreadableProbes;
    private int ioErrorRun;
    /** True once the sweep aborted on a dead link; the results are PARTIAL. */
    private boolean linkLost;
    private int linkLostAtDid;
    /** True once this ECU is known to report the supported subset of a batch. */
    private boolean subsetReportingConfirmed;
    /** DIDs to try for an anchor before the built-in defaults. */
    private List<String> anchorCandidates = Collections.emptyList();

    /**
     * Offer DIDs this vehicle is known to use, tried ahead of the Nexon defaults.
     *
     * findAnchorDid() walks BmsFields' DEFAULT DIDs, so on a model already
     * remapped by a previous scan none of them return data, no anchor is found,
     * and the batch pre-filter is written off for the whole sweep - turning one
     * request per empty group of three back into four. The overrides are exactly
     * the DIDs most likely to answer on such a vehicle.
     *
     * Passed in rather than read here: this class is pure Java so the self-test
     * can drive it, and Prefs is an Android class.
     */
    void setAnchorCandidates(List<String> dids) {
        anchorCandidates = dids == null ? Collections.<String>emptyList() : dids;
    }

    List<Hit> hits() {
        return hits;
    }

    boolean linkLost() {
        return linkLost;
    }

    /** First DID of the group where the dead link was noticed. */
    int linkLostAtDid() {
        return linkLostAtDid;
    }

    /**
     * Scan {@code from}..{@code to} inclusive. Batches 3 DIDs per request for
     * speed, then confirms each batch that answered by reading its DIDs singly -
     * a batched reply of unknown DIDs cannot be split, so confirmation is where
     * the per-DID length is actually learned.
     */
    void scan(ElmClient elm, int from, int to, Progress progress) throws IOException {
        hits.clear();
        batchProbeUsable = true;
        unreadableProbes = 0;
        ioErrorRun = 0;
        linkLost = false;
        linkLostAtDid = 0;
        subsetReportingConfirmed = calibrateSubsetReporting(elm);
        int total = Math.max(1, to - from + 1);
        for (int did = from; did <= to; did += 3) {
            List<String> group = new ArrayList<>(3);
            for (int d = did; d < Math.min(did + 3, to + 1); d++) {
                // Locale.ROOT: this string is TRANSMITTED. Under a locale with a
                // non-Latin numbering system %X emits Arabic-Indic digits and
                // every DID in the sweep would go out malformed.
                group.add(String.format(Locale.ROOT, "%04X", d));
            }

            boolean anyAnswered;
            if (!batchProbeUsable || !subsetReportingConfirmed) {
                // Nothing to learn, so do not pay for the round trip.
                //
                // The probe can only SKIP a group on an ECU proven to report the
                // supported subset of a batch. Without that proof every branch
                // below ends in anyAnswered = true, so the request was pure
                // overhead - one extra round trip per group of three, 33% more
                // traffic, on precisely the unproven ECUs the pre-filter was
                // meant to accelerate. Across a 0000-FFFF sweep that is ~21,845
                // wasted requests, tens of minutes of it.
                anyAnswered = true;
            } else {
                try {
                    // A raw batched request: we cannot split it (unknown widths),
                    // but a positive reply tells us at least one of the three
                    // exists. Decode properly rather than searching for "62":
                    // that substring also occurs in the CAN id (a BMS on 0x75A
                    // replies on 0x762) and in ordinary data bytes, which would
                    // make every group look like a hit.
                    StringBuilder req = new StringBuilder("22");
                    for (String g : group) req.append(g);
                    String text = elm.request(req.toString(), 2000);
                    ioErrorRun = 0;
                    UdsCodec.Response probe = UdsCodec.decode22(text, elm.responseId());
                    if (probe != null && !probe.negative) {
                        anyAnswered = true;                    // something is there
                        unreadableProbes = 0;
                    } else if (probe != null && probe.nrc == NRC_OUT_OF_RANGE) {
                        // "None of these three exist". Safe to skip only because
                        // the guard above has already established that this ECU
                        // reports the supported subset - many ECUs instead answer
                        // 0x31 when just one member is absent, which would silently
                        // drop real DIDs from the map.
                        anyAnswered = false;
                        unreadableProbes = 0;
                    } else {
                        // Anything else means the batched FORM was refused, not
                        // that the DIDs are absent: NRC 0x13 from an ECU that
                        // rejects multi-DID 0x22, or an undecodable "?" from a
                        // clone. Trusting it would report "0 DIDs answered" for a
                        // BMS whose single reads work fine - precisely the vehicle
                        // this scan exists to serve.
                        if (probe != null && probe.nrc == NRC_INVALID_LENGTH) {
                            batchProbeUsable = false;
                        } else if (++unreadableProbes >= BATCH_PROBE_GIVEUP) {
                            batchProbeUsable = false;
                        }
                        anyAnswered = true;                    // verify by single reads
                    }
                } catch (IOException e) {
                    countIoError();
                    anyAnswered = true;   // on error, fall through to single reads
                }
            }

            if (anyAnswered) {
                for (String code : group) {
                    if (linkLost) break;
                    UdsCodec.Response r = safeRead(elm, code);
                    if (r == null || !r.isData()) continue;
                    String first = UdsCodec.toHex(r.data);
                    UdsCodec.Response again = safeRead(elm, code);
                    String second = (again != null && again.isData())
                            ? UdsCodec.toHex(again.data) : null;
                    hits.add(new Hit(code, r.data.length, first, second));
                }
            }

            // Abort on a dead link. Without this a dead adapter made every
            // remaining read return instantly-null and the sweep "completed"
            // with each unasked DID recorded as a non-responder - a truncated
            // report that looks exactly like a finished one. Checked at the
            // BOTTOM of the body: a link dying inside the FINAL group would
            // otherwise exit via the loop condition with linkLostAtDid never
            // written, and the warning would name DID 0000.
            if (linkLost) {
                linkLostAtDid = did;
                return;
            }

            if (progress != null) {
                int pct = (int) (100L * (did - from + 1) / total);
                if (!progress.onProgress(did, hits.size(), pct)) return;
            }
        }
    }

    /**
     * Does this ECU report the supported SUBSET of a multi-DID request?
     *
     * Asked with one DID known to exist plus two that cannot: an ECU that answers
     * positively reports subsets (so a 0x31 really does mean "none of these
     * exist"), while one that answers 0x31 is all-or-nothing and its 0x31 tells
     * us nothing about the individual DIDs.
     */
    private boolean calibrateSubsetReporting(ElmClient elm) {
        String anchor = findAnchorDid(elm);
        if (anchor == null) return false;      // nothing proven: take the cautious path
        try {
            String text = elm.request("22" + anchor + "FFFEFFFD", 2500);
            UdsCodec.Response r = UdsCodec.decode22(text, elm.responseId());
            return r != null && !r.negative;
        } catch (IOException e) {
            return false;      // assume the cautious path
        }
    }

    /**
     * A DID this ECU is proven to answer, so the subset probe means something.
     *
     * This used to be hardcoded to F197. The app deliberately handles a BMS that
     * serves data DIDs but not that identification DID - and on exactly such a
     * vehicle the probe came back negative, the pre-filter was written off for
     * the whole sweep, and every group of three fell through to single reads.
     * The cost of finding an anchor is a handful of reads against a sweep of up
     * to 65536.
     */
    private String findAnchorDid(ElmClient elm) {
        List<String> tries = new ArrayList<>();
        tries.add(BmsFields.DID_SYSTEM_NAME);
        tries.addAll(anchorCandidates);
        for (BmsFields.Field f : BmsFields.ALL) {
            if (!tries.contains(f.did)) tries.add(f.did);
        }
        for (String did : tries) {
            UdsCodec.Response r = safeRead(elm, did);
            if (r != null && r.isData()) return did;
        }
        return null;
    }

    private UdsCodec.Response safeRead(ElmClient elm, String did) {
        try {
            UdsCodec.Response r = elm.readDid(did);
            ioErrorRun = 0;
            return r;
        } catch (IOException e) {
            countIoError();
            return null;
        }
    }

    private void countIoError() {
        if (++ioErrorRun >= LINK_DEAD_LIMIT) linkLost = true;
    }

    /**
     * A candidate reading of the voltage group.
     *
     * Pack voltage and cell voltage CANNOT be told apart by magnitude alone: for a
     * ~100-cell pack, pack volts in tenths (e.g. 3463 = 346.3 V) sits inside the
     * cell-millivolt range (2500..4300). Worse, rival readings can each be
     * self-consistent - 346.3 V over 104 cells of 3.328 V, or 332.8 V over 96
     * cells of 3.463 V. So rather than guess, we enumerate the physically
     * coherent readings and let a human pick the one that matches their car.
     *
     * Cells come in two units. The Gotion controller serves millivolts; the
     * TacoGotion one, and the Tiago's VECU mirror of it, serve 10 mV counts
     * (325 = 3.25 V). Either way a cell must land in 2.0-4.5 V - the gate that
     * keeps a constant 5000 placeholder from being offered as a cell.
     */
    static final class VoltageHypothesis {
        final String packDid;
        final String cellMaxDid;
        final String cellMinDid;
        final double packVolts;
        final double cellMaxVolts;
        final double cellMinVolts;
        final double series;
        final double disagreement;   // lower is more self-consistent
        /** 1 or 10: millivolts per count of the cell DIDs. */
        final int cellUnitMv;
        /** The pack DID changed between the two samples - a live pack, not a constant. */
        final boolean packMoved;
        /**
         * The two cell DIDs sit within two codes of each other.
         *
         * Every controller this app has seen lays the pair out that way -
         * 3415/3417 on the Gotion, 3477/3478 on the Tiago's mirror, 3017/3018
         * on the $30xx family - because they are neighbouring signals in one
         * catalog block. A coincidental pairing with an unrelated DID (a SOC in
         * tenths reading 330 next to cells reading 331 and 330) is not adjacent,
         * so this is what ranks the real pair above readings that are MORE
         * self-consistent than the truth.
         *
         * Computed once here: {@link #BY_QUALITY} runs inside an O(n^3) search,
         * so parsing hex in the comparator would cost the whole sweep.
         */
        final boolean cellsAdjacent;

        VoltageHypothesis(String packDid, String cellMaxDid, String cellMinDid,
                          double packVolts, double cellMaxVolts, double cellMinVolts,
                          double series, double disagreement, int cellUnitMv, boolean packMoved) {
            this.cellsAdjacent = adjacent(cellMaxDid, cellMinDid);
            this.packDid = packDid;
            this.cellMaxDid = cellMaxDid;
            this.cellMinDid = cellMinDid;
            this.packVolts = packVolts;
            this.cellMaxVolts = cellMaxVolts;
            this.cellMinVolts = cellMinVolts;
            this.series = series;
            this.disagreement = disagreement;
            this.cellUnitMv = cellUnitMv;
            this.packMoved = packMoved;
        }

        /** Two DIDs within two codes of each other; false if either is not hex. */
        private static boolean adjacent(String a, String b) {
            try {
                return Math.abs(Integer.parseInt(a, 16) - Integer.parseInt(b, 16)) <= 2;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        String describe() {
            return String.format(Locale.ROOT,
                    "pack %.1f V = %.0f x %.3f V  (pack %s, max %s, min %s)%s",
                    packVolts, series, (cellMaxVolts + cellMinVolts) / 2,
                    packDid, cellMaxDid, cellMinDid,
                    cellUnitMv == 10 ? "  cells in 10 mV" : "");
        }
    }

    /**
     * Enumerate self-consistent readings of the voltage group, best first.
     *
     * A reading is coherent when one candidate treated as pack volts divided by
     * two others treated as cell millivolts yields the SAME plausible series
     * count both times. Ranked by how closely those two counts agree, then by how
     * near the count is to a whole number of cells.
     */
    /**
     * Best-first: a pack DID that MOVED between samples first (a constant is a
     * rail or a rating, not a pack), then a pair of ADJACENT cell DIDs, then
     * closest agreement, then nearest a whole cell count.
     *
     * Adjacency outranks agreement deliberately. A coincidental pairing can be
     * MORE self-consistent than the truth - on a TacoGotion pack whose SOC in
     * tenths reads 330 while its cells read 331 and 330, treating the SOC as a
     * cell agrees exactly while the real pair disagrees by 0.3 groups - and the
     * first hypothesis is the one the screen offers first and the one the SOC
     * exclusion trusts. Layout is the stronger evidence: cells come from one
     * catalog block, an SOC does not.
     */
    private static final java.util.Comparator<VoltageHypothesis> BY_QUALITY =
            (x, y) -> {
                if (x.packMoved != y.packMoved) return x.packMoved ? -1 : 1;
                if (x.cellsAdjacent != y.cellsAdjacent) return x.cellsAdjacent ? -1 : 1;
                int c = Double.compare(x.disagreement, y.disagreement);
                if (c != 0) return c;
                double dx = Math.abs(x.series - Math.round(x.series));
                double dy = Math.abs(y.series - Math.round(y.series));
                return Double.compare(dx, dy);
            };

    /**
     * Most 2-byte responders in the voltage band to search.
     *
     * The search is O(n^3), so this bounds TIME as well as memory: 140 responders
     * is ~2.7 M iterations, which is a second or so on a phone. A wide sweep can
     * turn up several hundred, and 800 would be 512 M iterations - minutes of
     * freeze before the results are published, losing the whole sweep.
     */
    private static final int MAX_BAND = 140;
    /** Hypotheses retained while searching; only 6 survive de-duplication. */
    private static final int MAX_KEPT = 64;

    /**
     * A hypothesis search and what it had to leave out, together.
     *
     * The drop count used to live in a static that only voltageHypotheses() set,
     * so scanNote() described whichever search ran last - and writeReport() skips
     * the search when hypotheses are passed in, so a report could carry a "NOTE: N
     * responders were left out" line belonging to a previous analysis. Carrying
     * the count with its own result makes that impossible.
     */
    static final class Analysis {
        final List<VoltageHypothesis> hypotheses;
        final int dropped;

        Analysis(List<VoltageHypothesis> hypotheses, int dropped) {
            this.hypotheses = hypotheses;
            this.dropped = dropped;
        }

        /** Anything this analysis had to leave out, or "" when it was exhaustive. */
        String note() {
            return dropped == 0 ? "" : dropped + " voltage-band responders were left "
                    + "out of the reading search to keep it bounded; the suggestions "
                    + "below may not be the best available.";
        }
    }

    static Analysis analyse(List<Hit> hits) {
        int[] dropped = new int[1];
        List<VoltageHypothesis> h = voltageHypotheses(hits, dropped);
        return new Analysis(h, dropped[0]);
    }

    static List<VoltageHypothesis> voltageHypotheses(List<Hit> hits) {
        return voltageHypotheses(hits, new int[1]);
    }

    /** Pack candidates: two bytes, 150-900 V at 0.1 V per count. */
    private static boolean packBand(Hit h) {
        return h.length == 2 && h.first() >= 1500 && h.first() <= 9000;
    }

    /** Cells in 1 mV: 2.000-4.500 V, the plausibility window for any Li-ion cell. */
    private static boolean cellBand1(Hit h) {
        return h.length == 2 && h.first() >= 2000 && h.first() <= 4500;
    }

    /** Cells in 10 mV: the same window, 200-450 counts. */
    private static boolean cellBand10(Hit h) {
        return h.length == 2 && h.first() >= 200 && h.first() <= 450;
    }

    private static List<VoltageHypothesis> voltageHypotheses(List<Hit> hits,
                                                             int[] droppedOut) {
        List<VoltageHypothesis> out = new ArrayList<>();
        if (hits == null) return out;

        List<Hit> packs = new ArrayList<>(), cells1 = new ArrayList<>(), cells10 = new ArrayList<>();
        java.util.Set<String> considered = new java.util.HashSet<>();
        for (Hit h : hits) {
            if (packBand(h)) packs.add(h);
            if (cellBand1(h)) cells1.add(h);
            if (cellBand10(h)) cells10.add(h);
            if (packBand(h) || cellBand1(h) || cellBand10(h)) considered.add(h.did);
        }
        cap(packs);
        cap(cells1);
        cap(cells10);
        // The note counts RESPONDERS left out, and the bands overlap - the pack
        // band contains every 1 mV cell candidate. Summing each band's trim would
        // report one responder twice, so count the distinct ones no band kept.
        java.util.Set<String> kept = new java.util.HashSet<>();
        for (Hit h : packs) kept.add(h.did);
        for (Hit h : cells1) kept.add(h.did);
        for (Hit h : cells10) kept.add(h.did);
        droppedOut[0] = considered.size() - kept.size();
        search(out, packs, cells1, 1);
        search(out, packs, cells10, 10);

        // Keep one hypothesis per (cellMax, cellMin) pairing: duplicate pack sense
        // points otherwise flood the list with the same physical reading.
        List<VoltageHypothesis> unique = new ArrayList<>();
        for (VoltageHypothesis h : out) {
            boolean seen = false;
            for (VoltageHypothesis u : unique) {
                if (u.cellMaxDid.equals(h.cellMaxDid) && u.cellMinDid.equals(h.cellMinDid)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) unique.add(h);
            if (unique.size() >= 6) break;
        }
        return unique;
    }

    /** Trim a band to MAX_BAND in place; the search is O(n^3) in the band size. */
    private static void cap(List<Hit> band) {
        while (band.size() > MAX_BAND) band.remove(band.size() - 1);
    }

    /**
     * Enumerate self-consistent (pack, cellMax, cellMin) readings for one cell
     * unit. A reading is coherent when the pack divided by either cell gives the
     * SAME plausible series count. Bounded insert, kept sorted: collecting every
     * match and sorting afterwards once retained ~1 GB of objects on a wide sweep.
     */
    private static void search(List<VoltageHypothesis> out, List<Hit> packs, List<Hit> cells,
                               int unitMv) {
        for (Hit p : packs) {
            for (Hit a : cells) {
                if (a == p) continue;
                for (Hit b : cells) {
                    if (b == p || b == a || a.first() < b.first()) continue;
                    double s1 = p.first() * 100.0 / (a.first() * unitMv);
                    double s2 = p.first() * 100.0 / (b.first() * unitMv);
                    if (s1 < 24 || s1 > 220 || s2 < 24 || s2 > 220) continue;
                    double disagree = Math.abs(s1 - s2);
                    if (disagree > 2.0) continue;         // not the same pack
                    double series = (s1 + s2) / 2.0;
                    VoltageHypothesis h = new VoltageHypothesis(p.did, a.did, b.did,
                            p.first() / 10.0, a.first() * unitMv / 1000.0,
                            b.first() * unitMv / 1000.0, series, disagree, unitMv, p.changed());
                    if (out.size() >= MAX_KEPT
                            && BY_QUALITY.compare(h, out.get(out.size() - 1)) >= 0) {
                        continue;
                    }
                    int i = out.size();
                    out.add(h);
                    while (i > 0 && BY_QUALITY.compare(out.get(i), out.get(i - 1)) < 0) {
                        Collections.swap(out, i, i - 1);
                        i--;
                    }
                    if (out.size() > MAX_KEPT) out.remove(out.size() - 1);
                }
            }
        }
    }

    /**
     * Propose DIDs for the roles that CAN be identified unambiguously.
     *
     * Deliberately excludes pack and cell voltages - see {@link #voltageHypotheses}
     * for why those need a human. Validated against real Nexon EV Max scan data:
     * SOC, SOH, both cell indices and the temperatures all come out correct.
     *
     * The heuristics lean on physics rather than Tata's numbering, so they should
     * transfer between models:
     *   * a 2-byte value whose tenth lands in 0..100 is SOC or SOH; the one that
     *     moves between samples is SOC, the steadier high one is SOH
     *   * a 1-byte value that moves and stays within the cell-group count is an index
     *   * a steady 1-byte value in 20..120 (i.e. -20..80 C) is a temperature
     */
    static Map<String, String> suggest(List<Hit> hits) {
        return suggest(hits, hits == null ? null : voltageHypotheses(hits));
    }

    /**
     * DIDs the voltage search accounted for, which the SOC/SOH pick must skip.
     *
     * A 10 mV cell reads 200..450 counts, which is INSIDE the 0..1000 SOC
     * window, and on a car that is not parked it moves between samples - so the
     * "first 2-byte value that moved" rule in {@link #suggest} would name cell
     * 3477 as "SOC 32.5 %". Worse, having a soc_pct suggestion makes
     * ScanActivity.showSocChoice return early, so the owner is handed a wrong
     * SOC with no correction path on the screen.
     *
     * Only the 10 mV cells can actually collide: the pack band starts at 1500
     * counts and the 1 mV cell band at 2000, both above the 1000 the SOC pick
     * admits. The top reading's pack and cells are added anyway, so that
     * widening either band later cannot quietly reintroduce the bug - they are
     * belt and braces, not the load-bearing part.
     *
     * The 10 mV cells are taken only from readings whose two cell DIDs are
     * ADJACENT. A genuine tenths-of-a-percent SOC also reads 200..450, so the
     * search enumerates pairings that use it as a cell; excluding those too
     * would drop the real SOC. Adjacency is what tells them apart - see
     * {@link VoltageHypothesis#cellsAdjacent}.
     *
     * Whatever this removes is offered back on the screen by
     * {@link #socCandidatesTenths}, so no exclusion can leave an owner without
     * a way to map SOC.
     */
    private static java.util.Set<String> voltageDids(List<VoltageHypothesis> vh) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (vh == null || vh.isEmpty()) return out;
        VoltageHypothesis top = vh.get(0);
        out.add(top.packDid);
        out.add(top.cellMaxDid);
        out.add(top.cellMinDid);
        for (VoltageHypothesis h : vh) {
            if (h.cellUnitMv == 10 && h.cellsAdjacent) {
                out.add(h.cellMaxDid);
                out.add(h.cellMinDid);
            }
        }
        return out;
    }

    /** As {@link #suggest(List)}, reusing hypotheses already computed. */
    static Map<String, String> suggest(List<Hit> hits, List<VoltageHypothesis> vh) {
        Map<String, String> out = new LinkedHashMap<>();
        if (hits == null || hits.isEmpty()) return out;

        java.util.Set<String> voltageDids = voltageDids(vh);

        List<Hit> pct = new ArrayList<>();
        List<Hit> bytes = new ArrayList<>();
        for (Hit h : hits) {
            int v = h.first();
            if (v < 0) continue;
            if (h.length == 2 && v <= 1000) {
                if (!voltageDids.contains(h.did)) pct.add(h);
            } else if (h.length == 1) bytes.add(h);
        }

        // SOC moves; SOH sits still and reads high. Both are u16 tenths of a percent.
        Hit soc = null, soh = null;
        for (Hit h : pct) {
            if (h.changed() && h.first() > 0 && soc == null) soc = h;
        }
        for (Hit h : pct) {
            if (h == soc) continue;
            int v = h.first();
            if (v >= 700 && v <= 1000 && (soh == null || v > soh.first())) soh = h;
        }
        if (soc != null) out.put("soc_pct", soc.did);
        if (soh != null) out.put("soh_pct", soh.did);

        // Bound index candidates by the best guess at the cell-group count.
        int series = (vh == null || vh.isEmpty()) ? 0 : (int) Math.round(vh.get(0).series);

        List<Hit> idx = new ArrayList<>();
        for (Hit h : bytes) {
            int v = h.first();
            if (v >= 1 && (series == 0 ? v <= 200 : v <= series) && h.changed()) idx.add(h);
        }
        // WHICH INDEX IS WHICH CANNOT BE MEASURED FROM A SCAN.
        //
        // Two static samples cannot separate a minimum index from a maximum one:
        // both move, both sit inside 1..series, and nothing in a snapshot says
        // which extreme a label points at. Only CURRENT can settle it - a group
        // carrying excess resistance sags on discharge and rises on charge, so it
        // swaps indices when the current reverses - and a scan collects no
        // current at all.
        //
        // What follows is therefore an ORDERING ASSUMPTION, not a measurement.
        // On the Nexon EV Max the minimum sits on the HIGHER of the two DIDs
        // (341A) and the maximum on the lower (3419), established by that sign
        // reversal on a logged drive. Assigning by list position instead - which
        // is ascending DID order - silently reproduced the pre-v3.3 mapping and
        // inverted every downstream consumer: the dashboard tiles, the alerter's
        // lock-on window, the pack map, and every CSV row written afterwards.
        //
        // ScanActivity offers a swap, because this is a guess a human can check
        // against their own car and the app cannot.
        if (idx.size() >= 2) {
            Hit first = idx.get(0), second = idx.get(1);
            Hit lower = first.did.compareToIgnoreCase(second.did) <= 0 ? first : second;
            Hit higher = lower == first ? second : first;
            out.put("cell_max_idx", lower.did);
            out.put("cell_min_idx", higher.did);
        }
        // A single candidate is deliberately left unmapped: with nothing to pair
        // it against there is no basis at all for calling it min rather than max,
        // and guessing would be worse than leaving the default in place.

        // Temperatures: STEADY single bytes landing in -20..80 C after the offset.
        // Steadiness matters - without it a moving counter or a cell index gets
        // mapped as a temperature and pushes a real sensor out of the slot list.
        List<Hit> tempCandidates = new ArrayList<>();
        for (Hit h : bytes) {
            if (idx.contains(h) || h.changed()) continue;
            int v = h.first();
            if (v >= 20 && v <= 120) tempCandidates.add(h);
        }
        // Pack sensors read close to one another, so keep the cluster around the
        // median and drop outliers - which is what a stray index byte looks like.
        if (tempCandidates.size() > 1) {
            List<Integer> vals = new ArrayList<>();
            for (Hit h : tempCandidates) vals.add(h.first());
            Collections.sort(vals);
            int median = vals.get(vals.size() / 2);
            List<Hit> clustered = new ArrayList<>();
            for (Hit h : tempCandidates) {
                if (Math.abs(h.first() - median) <= 15) clustered.add(h);
            }
            if (!clustered.isEmpty()) tempCandidates = clustered;
        }
        // Derived from the field map rather than hardcoded. The list used to
        // carry a temp_e_c slot that BmsFields no longer has, so a fifth sensor
        // was written to an override key nothing reads - and the sensor was
        // silently dropped rather than mapped.
        List<String> tempKeys = new ArrayList<>();
        for (BmsFields.Field f : BmsFields.ALL) {
            if (f.kind == BmsFields.Kind.U8_TEMP) tempKeys.add(f.key);
        }
        int slot = 0;
        for (Hit h : tempCandidates) {
            if (slot >= tempKeys.size()) break;
            out.put(tempKeys.get(slot++), h.did);
        }
        return out;
    }

    /**
     * DIDs that could be a state of charge in WHOLE percent, for a human to
     * match against the dash. suggest() only finds a tenths-of-a-percent SOC
     * that moved between samples; a parked car's whole-percent SOC does neither,
     * so it is offered rather than guessed. Values 0..100, one or two bytes,
     * not a suggested role, not a cell or pack candidate; the ones that moved
     * first; at most twelve so the screen stays a list and not a sweep.
     */
    static List<Hit> socCandidates(List<Hit> hits, Map<String, String> suggestion) {
        List<Hit> out = new ArrayList<>();
        if (hits == null) return out;
        java.util.Set<String> taken = new java.util.HashSet<>();
        if (suggestion != null) taken.addAll(suggestion.values());
        for (Hit h : hits) {
            int v = h.first();
            if (v < 0 || v > 100 || h.length > 2) continue;
            if (taken.contains(h.did) || cellBand10(h) || packBand(h)) continue;
            out.add(h);
        }
        Collections.sort(out, (x, y) -> {
            if (x.changed() != y.changed()) return x.changed() ? -1 : 1;
            return x.did.compareTo(y.did);
        });
        return out.size() > 12 ? new ArrayList<>(out.subList(0, 12)) : out;
    }

    /**
     * DIDs that could be a state of charge in TENTHS of a percent but were set
     * aside as pack or cell voltages, for a human to match against the dash.
     *
     * The safety net under {@link #voltageDids}. Pack volts, 10 mV cells and a
     * tenths SOC are all 2-byte counts in overlapping ranges, so any rule that
     * keeps a moving cell from being named "SOC 32.5 %" can also catch a real
     * SOC that happens to read like a cell - roughly when SOC % equals ten times
     * the cell voltage, e.g. 33.0 % on cells at 3.30 V. When that happens the
     * suggestion is empty and the whole-percent list in {@link #socCandidates}
     * cannot help either, because it only admits values up to 100.
     *
     * So whatever the exclusion removed is offered here instead: 2-byte,
     * 101..1000 (below 101 is the whole-percent list's business), and MOVING,
     * because a state of charge that never changes between two samples is a
     * constant, not a reading. Applied with a 0.1 scale rather than 1.
     *
     * @param vh         the same hypotheses the suggestion was built from
     * @param suggestion roles already mapped, which are not offered again
     */
    static List<Hit> socCandidatesTenths(List<Hit> hits, List<VoltageHypothesis> vh,
                                         Map<String, String> suggestion) {
        List<Hit> out = new ArrayList<>();
        if (hits == null) return out;
        java.util.Set<String> excluded = voltageDids(vh);
        java.util.Set<String> taken = new java.util.HashSet<>();
        if (suggestion != null) taken.addAll(suggestion.values());
        for (Hit h : hits) {
            int v = h.first();
            if (h.length != 2 || v <= 100 || v > 1000) continue;
            if (!excluded.contains(h.did) || taken.contains(h.did)) continue;
            if (!h.changed()) continue;
            out.add(h);
        }
        Collections.sort(out, (x, y) -> x.did.compareTo(y.did));
        return out.size() > 12 ? new ArrayList<>(out.subList(0, 12)) : out;
    }

    /**
     * Write a shareable report of the scan, including the suggested mapping.
     *
     * @param preamble lines the caller knows and this class cannot - the range
     *                 swept, when, which app version, what the adapter said -
     *                 written right under the title. "" for none.
     */
    static File writeReport(File dir, String bmsId, String preamble, List<Hit> hits,
                            Map<String, String> suggestion,
                            Analysis analysis, String warning) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        File out = new File(dir, "didscan_" + stamp + ".txt");
        try (FileWriter w = new FileWriter(out)) {
            w.write("Tata EV BMS - DID scan\n");
            w.write("BMS request id: " + bmsId + "\n");
            if (preamble != null && !preamble.isEmpty()) {
                w.write(preamble.endsWith("\n") ? preamble : preamble + "\n");
            }
            w.write("Responders: " + hits.size() + "\n");
            // The report is the artifact people map from on a desktop, so a
            // truncated sweep must say so HERE, not only on the phone screen -
            // a partial report with no marker reads as a complete one.
            if (warning != null && !warning.isEmpty()) {
                w.write("\n*** " + warning + " ***\n");
            }
            w.write("\n");
            w.write("DID\tbytes\tsample1\tsample2\tchanged\n");
            for (Hit h : hits) {
                w.write(h.did + "\t" + h.length + "\t" + h.firstHex + "\t"
                        + (h.secondHex == null ? "" : h.secondHex) + "\t"
                        + (h.changed() ? "yes" : "no") + "\n");
            }
            w.write("\nVoltage readings - PICK ONE, they cannot be told apart automatically:\n");
            Analysis a = analysis == null ? analyse(hits) : analysis;
            List<VoltageHypothesis> vh = a.hypotheses;
            // The note travels WITH the analysis, so it can only ever describe
            // this one.
            if (!a.note().isEmpty()) w.write("  NOTE: " + a.note() + "\n");
            if (vh.isEmpty()) {
                w.write("  (none found)\n");
            } else {
                for (int i = 0; i < vh.size(); i++) {
                    w.write("  [" + (i + 1) + "] " + vh.get(i).describe() + "\n");
                }
            }
            List<Hit> soc = socCandidates(hits, suggestion);
            if (!soc.isEmpty()) {
                w.write("\nWhole-percent SOC candidates - match one against the dash:\n");
                for (Hit h : soc) {
                    w.write("  " + h.did + " = " + h.first() + (h.changed() ? "  (moved)" : "") + "\n");
                }
            }
            w.write("\nSuggested mapping for the unambiguous roles:\n");
            if (suggestion.isEmpty()) {
                w.write("  (nothing confident enough to suggest)\n");
            } else {
                for (Map.Entry<String, String> e : suggestion.entrySet()) {
                    w.write("  " + e.getKey() + " = " + e.getValue() + "\n");
                }
            }
        }
        return out;
    }
}
