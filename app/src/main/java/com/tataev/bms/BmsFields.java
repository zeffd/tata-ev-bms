package com.tataev.bms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * BMS parameter map for Tata EVs.
 *
 * Discovered on a 2023 Nexon EV Max (BMS at request 0x785 / response 0x78D,
 * supplier GOTION_BMS). Tata's EV range shares an electrical architecture, so
 * the same non-standard 0x7xx diagnostic addressing and DID block are expected
 * on Tiago/Tigor/Punch/Curvv EV - but cell count and the exact DID set can
 * differ, so nothing here is assumed:
 *
 *   * the BMS address is auto-detected by probing candidates for F197 == "BMS"
 *   * series cell count is DERIVED (pack V / mean cell V), never hardcoded
 *   * a DID that does not answer simply reads as "--" instead of breaking
 *
 * Scalings below were cross-checked on the Nexon EV Max: pack voltage over mean
 * cell voltage landed on 104.2 series groups, and the cell-index fields never
 * exceeded that. The CURRENT scaling is NOT confirmed - the raw value sits just
 * under 0x8000 (a signed midpoint encoding) but the multiplier could not be
 * pinned down on a stationary car, so it is user-settable and raw values are
 * always logged for later recalibration.
 */
final class BmsFields {

    enum Kind { U16_DIV10, U16_DIV1000, U16_RAW, I16_RAW, U16_MV, U8_RAW, U8_TEMP,
                CURRENT_MID }

    static final class Field {
        final String did;
        final String key;
        final String label;
        final String unit;
        final Kind kind;
        final boolean primary;

        Field(String did, String key, String label, String unit, Kind kind, boolean primary) {
            this.did = did;
            this.key = key;
            this.label = label;
            this.unit = unit;
            this.kind = kind;
            this.primary = primary;
        }
    }

    /**
     * Diagnostic request IDs to probe when locating the BMS. Ordered by
     * likelihood: the known Nexon address first, then the rest of the 0x78x
     * block, then the other IDs that answered UDS on the Nexon, then the
     * ISO-standard addresses in case another model uses them.
     */
    static final List<String> BMS_CANDIDATES = Collections.unmodifiableList(Arrays.asList(
            "785", "786", "787", "788", "789", "78A", "78B", "780", "781", "782",
            "783", "784", "790", "791", "7A5", "7B5",
            "700", "701", "702", "703", "705", "710", "723", "730",
            "750", "752", "754",
            "7E0", "7E1", "7E2", "7E3", "7E4", "7E5", "7E6", "7E7"
    ));

    /** UDS DID holding the ECU's own name; how we recognise the BMS. */
    static final String DID_SYSTEM_NAME = "F197";
    static final String DID_SUPPLIER = "F18A";
    /** UDS DID carrying the VIN; keys the per-vehicle profile. */
    static final String DID_VIN = "F190";

    static final List<Field> ALL;

    static {
        List<Field> f = new ArrayList<>();
        // --- dashboard ---
        //
        // NOTE ON THE CELL INDICES. 3419 is the MAXIMUM index and 341A is the
        // MINIMUM - the opposite of what DID adjacency suggests, and the opposite
        // of what this app shipped with up to v3.2.
        //
        // Proven by sign reversal, not by correlation. A group whose internal
        // resistance is higher than its neighbours sags under discharge
        // (V = OCV - I*R) and rises under charge (V = OCV + I*R), so it must swap
        // which index reports it when the current reverses. On a logged drive,
        // group 77 does exactly that:
        //
        //     discharge > 25 A : 341A reports it 60% of the time, 3419 NEVER (0/101)
        //     regen     < -25 A: 3419 reports it 26%, 341A almost never (2.6%)
        //
        // Under the old labelling that same measurement would require group 77 to
        // read HIGHEST while sourcing current and LOWEST while absorbing it - a
        // negative internal resistance. The flipped labelling is just Ohm's law.
        f.add(new Field("3402", "soc_pct",      "SOC",      "%",  Kind.U16_DIV10,   true));
        f.add(new Field("3403", "soh_pct",      "SOH",      "%",  Kind.U16_DIV10,   true));
        f.add(new Field("3400", "pack_v",       "Pack",     "V",  Kind.U16_DIV10,   true));
        f.add(new Field("3401", "current_a",    "Current",  "A",  Kind.CURRENT_MID, true));
        f.add(new Field("3417", "cell_min_mv",  "Cell min", "mV", Kind.U16_MV,      true));
        f.add(new Field("341A", "cell_min_idx", "Min idx",  "",   Kind.U8_RAW,      true));
        f.add(new Field("3415", "cell_max_mv",  "Cell max", "mV", Kind.U16_MV,      true));
        f.add(new Field("3419", "cell_max_idx", "Max idx",  "",   Kind.U8_RAW,      true));
        f.add(new Field("3409", "temp_a_c",     "Temp 1",   "C",  Kind.U8_TEMP,     true));
        f.add(new Field("340B", "temp_b_c",     "Temp 2",   "C",  Kind.U8_TEMP,     true));
        f.add(new Field("3411", "temp_c_c",     "Temp 3",   "C",  Kind.U8_TEMP,     true));
        f.add(new Field("3412", "temp_d_c",     "Temp 4",   "C",  Kind.U8_TEMP,     true));
        // --- logged only ---
        // 3410 held 0x28 for every sample of the first on-vehicle log while all
        // four real probes sat at 29-30 C. Decoding it as U8_TEMP printed a
        // confident "0 C" for a channel that is not a temperature at all, so it
        // is logged as the raw byte it is.
        f.add(new Field("3410", "d3410",        "d3410",    "",   Kind.U8_RAW,      false));
        // Tracked 3400 within 0.2 V and then collapsed to 1.60 V the instant the
        // car shut down - the contactor/DC-link side, not a second sense point on
        // the battery. Across a loaded drive it sat 0.21 V from pack voltage on
        // average, which is the drop across a closed contactor.
        f.add(new Field("3482", "link_v",       "Link",     "V",  Kind.U16_DIV10,   false));
        // SIGNED, and NOT a current: it held 0xFDE8 through a 100 A load swing
        // without moving once, so the "cur_raw2" name it carried was a guess the
        // data has now refuted. Named for its DID until something identifies it.
        f.add(new Field("3413", "d3413",        "d3413",    "",   Kind.I16_RAW,     false));
        // CONFIRMED on a loaded drive: held 13.741-13.754 V across 388 samples
        // while the pack swung -216 to +322 counts, and decayed 13.727 -> 13.332
        // once the contactors opened. Only a DC-DC-regulated rail is that
        // indifferent to what the traction pack is doing. Shown on the dashboard
        // because a tired 12 V battery strands an EV as surely as a flat pack,
        // and nothing else in the car tells the driver about it.
        f.add(new Field("3492", "aux_12v_v",    "12V aux",  "V",  Kind.U16_DIV1000, true));
        // Still unidentified. Responds to load (R^2 0.86 against pack voltage,
        // 0.78 against current) but not at all to SOC (R^2 0.001, flat mean across
        // a whole session), and smoothing the current made the fit worse - so it
        // is not a filtered current either.
        f.add(new Field("347C", "d347C",        "d347C",    "",   Kind.U16_RAW,     false));
        // Accumulators, not clocks: the RATE tracks mean current (+105 counts ->
        // 5.3/min, +46 -> 2.3/min, +23 -> 0.8/min, and 0.56/min parked). They
        // track each other at a fixed offset of 89-92, so they are two views of
        // one quantity. The unit is still unknown - a full charge cycle would
        // give the scale.
        f.add(new Field("347F", "accum_a",      "Accum A",  "",   Kind.U16_RAW,     false));
        f.add(new Field("3480", "accum_b",      "Accum B",  "",   Kind.U16_RAW,     false));
        // CONFIRMED: pack voltage at 1 V resolution. Equals round(3400/10) exactly
        // on 178/360 loaded samples and within 1 V on 318/360, the remainder being
        // the skew between two batched reads. The static sweeps could not see this
        // because 345.8 and 346.0 both round to 346.
        f.add(new Field("3484", "pack_v_1v",    "Pack 1V",  "V",  Kind.U16_RAW,     false));
        // Matched (cell_max_mv - cell_min_mv) on 20 of 36 samples and differed by
        // exactly +/-1 mV on every other one - the skew between two batched reads
        // ~250 ms apart. Same quantity, straight from the BMS.
        f.add(new Field("34D5", "cell_delta_bms_mv", "BMS delta", "mV", Kind.U16_RAW, false));
        f.add(new Field("3481", "d3481",        "d3481",    "",   Kind.U16_RAW,     false));
        ALL = Collections.unmodifiableList(f);
    }

    /** Expected payload width in bytes, used to split batched replies. */
    static int width(Kind k) {
        switch (k) {
            case U8_RAW:
            case U8_TEMP:
                return 1;
            default:
                return 2;
        }
    }

    /** Look up a field by its DID, or null if we never asked for it. */
    static Field byDid(String did) {
        if (did == null) return null;
        for (Field f : ALL) {
            if (f.did.equalsIgnoreCase(did)) return f;
        }
        return null;
    }

    /**
     * The DID this field should actually be read from: a user override if one is
     * set for this vehicle, otherwise the default discovered on the Nexon EV Max.
     */
    static String effectiveDid(Field f, Prefs prefs) {
        if (prefs == null) return f.did;
        String o = prefs.didOverride(f.key);
        return (o == null || o.isEmpty()) ? f.did : o;
    }

    static List<Field> primary() {
        List<Field> out = new ArrayList<>();
        for (Field x : ALL) if (x.primary) out.add(x);
        return out;
    }

    static Double decode(Field f, byte[] data, double currentScale, int currentZero) {
        if (data == null) return null;
        switch (f.kind) {
            case U16_DIV10:
                return data.length == 2 ? u16(data) / 10.0 : null;
            case U16_DIV1000:
                return data.length == 2 ? u16(data) / 1000.0 : null;
            case U16_MV:
            case U16_RAW:
                return data.length == 2 ? (double) u16(data) : null;
            case I16_RAW:
                return data.length == 2 ? (double) i16(data) : null;
            case U8_RAW:
                return data.length == 1 ? (double) (data[0] & 0xFF) : null;
            case U8_TEMP:
                return data.length == 1 ? (double) ((data[0] & 0xFF) - 40) : null;
            case CURRENT_MID:
                return data.length == 2 ? (u16(data) - currentZero) * currentScale : null;
            default:
                return null;
        }
    }

    private static int u16(byte[] b) {
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    /** Two's-complement, for DIDs that carry a signed quantity. */
    private static int i16(byte[] b) {
        int v = u16(b);
        return v >= 0x8000 ? v - 0x10000 : v;
    }

    private BmsFields() { }
}
