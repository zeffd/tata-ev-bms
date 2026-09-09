package com.tataev.bms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * BMS parameter map for Tata EVs.
 *
 * Discovered on a 2023 Nexon EV Max (BMS at request 0x785 / response 0x78D,
 * supplier GOTION_BMS). Tata's EV range shares an electrical architecture, so
 * the same non-standard 0x7xx diagnostic addressing and DID block are expected
 * on Tiago/Tigor/Punch/Curvv EV - but cell count and the exact DID set can
 * differ, so nothing about the DATA is assumed:
 *
 *   * the BMS is addressed at 785 - per Tata's own diagnostic tool, across the
 *     passenger range - and nothing else on the bus is ever asked
 *   * series cell count is DERIVED (pack V / mean cell V), never hardcoded
 *   * a DID that does not answer simply reads as "--" instead of breaking
 *
 * Scalings were cross-checked on the Nexon EV Max and then confirmed by the
 * Gotion BMS catalog in Tata's service tool: pack voltage over mean cell voltage
 * landed on 104.2 series groups, the cell-index fields never exceeded that, and
 * the current's measured zero (raw 32000) and scale (0.1 A) are the catalog's
 * offset -3200 / resolution 0.1 to the digit. Current stays user-settable for a
 * model whose BMS encodes it differently.
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
        /** Drawn as a grid tile on the dashboard; polled whether or not recording. */
        final boolean primary;
        /**
         * Shown on the dashboard's one-line status strip (insulation, limits,
         * flags) rather than as a tile; polled like a primary field. A field is
         * never both.
         */
        final boolean status;

        Field(String did, String key, String label, String unit, Kind kind, boolean primary) {
            this(did, key, label, unit, kind, primary, false);
        }

        Field(String did, String key, String label, String unit, Kind kind, boolean primary,
              boolean status) {
            this.did = did;
            this.key = key;
            this.label = label;
            this.unit = unit;
            this.kind = kind;
            this.primary = primary;
            this.status = status;
        }
    }

    /**
     * The one address this app talks to: Tata's battery controller, 11-bit CAN
     * at 500 kbaud. It is where Tata's own diagnostic tool addresses the BMS
     * across the passenger range; replies come from 78D. Verified on the Nexon
     * EV Max. Nothing else on the bus is ever asked.
     */
    static final String BMS_REQUEST = "785";

    /**
     * What the two connect failures say. Shared, because the dashboard and the
     * Scan screen probe the controller the same way and had drifted into two
     * wordings for the same outcome.
     */
    static final String MSG_ADAPTER_SILENT =
            "The OBD adapter is not answering - check it is plugged in, powered and paired";
    static final String MSG_NO_REPLY =
            "No reply from the battery controller at " + BMS_REQUEST
                    + " - is the car switched on?";

    /** UDS DID holding the ECU's own name; how we recognise the BMS. */
    static final String DID_SYSTEM_NAME = "F197";
    static final String DID_SUPPLIER = "F18A";
    /**
     * ECU serial number: unique per pack, and the identity of a car whose
     * controller serves no VIN. Read only on that path.
     */
    static final String DID_SERIAL = "F18C";
    /** UDS DID carrying the VIN; keys the per-vehicle profile. */
    static final String DID_VIN = "F190";

    static final List<Field> ALL;

    static {
        List<Field> f = new ArrayList<>();
        // Names and scales follow the Gotion BMS catalog shipped with Tata's
        // service tool, which lists exactly the DIDs a Nexon EV Max answers at
        // 0x785. Every scale was checked against raw values logged on that car.
        //
        // --- dashboard tiles ---
        //
        // NOTE ON THE CELL INDICES. 3419 is the MAXIMUM index and 341A is the
        // MINIMUM - the opposite of what DID adjacency suggests, and the opposite
        // of what this app shipped with up to v3.2. The catalog names them
        // BMS_MaxCellVoltNo and BMS_MinCellVoltNo; a logged drive had already
        // proved it by sign reversal: group 77 sagged under discharge (341A named
        // it 60% of the time, 3419 never) and rose under regen (3419 26%, 341A
        // 2.6%), which only Ohm's law explains.
        f.add(new Field("3402", "soc_pct",      "SOC",      "%",  Kind.U16_DIV10,   true));
        f.add(new Field("3403", "soh_pct",      "SOH",      "%",  Kind.U16_DIV10,   true));
        f.add(new Field("3400", "pack_v",       "Pack",     "V",  Kind.U16_DIV10,   true));
        // Catalog: resolution 0.1, offset -3200 - i.e. (raw - 32000) x 0.1, the
        // exact zero and scale measured on the car before the catalog was read.
        f.add(new Field("3401", "current_a",    "Current",  "A",  Kind.CURRENT_MID, true));
        f.add(new Field("3417", "cell_min_mv",  "Cell min", "mV", Kind.U16_MV,      true));
        f.add(new Field("341A", "cell_min_idx", "Min idx",  "",   Kind.U8_RAW,      true));
        f.add(new Field("3415", "cell_max_mv",  "Cell max", "mV", Kind.U16_MV,      true));
        f.add(new Field("3419", "cell_max_idx", "Max idx",  "",   Kind.U8_RAW,      true));
        f.add(new Field("3409", "temp_a_c",     "Max temp", "C",  Kind.U8_TEMP,     true));
        f.add(new Field("340B", "temp_b_c",     "Min temp", "C",  Kind.U8_TEMP,     true));
        f.add(new Field("3411", "temp_c_c",     "Outlet temp", "C", Kind.U8_TEMP,   true));
        f.add(new Field("3412", "temp_d_c",     "Avg temp", "C",  Kind.U8_TEMP,     true));
        // A DC-DC-regulated rail: held 13.74 V through a 100 A swing and decayed
        // only once the contactors opened. A tired 12 V battery strands an EV as
        // surely as a flat pack, and nothing else in the car tells the driver.
        f.add(new Field("3492", "aux_12v_v",    "LV supply", "V", Kind.U16_DIV1000, true));

        // --- status strip: what the BMS is doing to the pack right now ---
        //
        // Insulation resistance saturates at 65000 kOhm when healthy and fell to
        // ~43000 mid-drive; water in the pack is what pulls it down.
        f.add(new Field("3413", "insulation_kohm", "Insulation", "kOhm", Kind.U16_RAW, false, true));
        // The current the BMS allows the motor to draw - a derating made visible.
        // 208.1 A at 82% SOC and 31 C on the Nexon EV Max.
        f.add(new Field("347C", "dis_limit_a",  "Discharge limit", "A", Kind.U16_DIV10, false, true));
        // Allowed regen power. These rose steadily during drives as the pack
        // warmed, which is why they were once mistaken for accumulators.
        f.add(new Field("347F", "regen_peak_kw", "Regen peak", "kW", Kind.U16_DIV10, false, true));
        // bit0 derate, bit1 balancing, bit2 leakage detect, bit3 charging,
        // bit4 HVIL detect, bit5 equalisation trigger. Words in BmsStatus.
        f.add(new Field("3479", "flags",        "Flags",    "",   Kind.U8_RAW,      false, true));
        // 0 none, 1 at 100%, 2 at 99%, 3 at 95%, 4 at 0%: when the SOC estimate
        // was last anchored - the number to read beside an SOC that jumps.
        f.add(new Field("3494", "soc_cal_state", "SOC cal", "",   Kind.U8_RAW,      false, true));
        f.add(new Field("340D", "fault_rank",   "Fault rank", "", Kind.U8_RAW,      false, true));

        // --- logged only ---
        // Coolant inlet. Raw 40 = 0 C on every Nexon EV Max sample: no sensor on
        // that variant. Logged as the temperature it is elsewhere.
        f.add(new Field("3410", "coolant_in_c", "Coolant in", "C", Kind.U8_TEMP,    false));
        // Busbar voltages either side of the contactors. The positive one tracked
        // pack volts within 0.2 V and collapsed to 1.6 V the instant the car shut
        // down; the key stays "link_v" so nothing downstream moves.
        f.add(new Field("3482", "link_v",       "Busbar +", "V",  Kind.U16_DIV10,   false));
        f.add(new Field("3481", "busbar_neg_v", "Busbar -", "V",  Kind.U16_DIV10,   false));
        // Pack voltage as the motor controller sees it, 1 V resolution.
        f.add(new Field("3484", "mcu_dc_v",     "VCU busbar", "V", Kind.U16_RAW,    false));
        // Matched (cell_max_mv - cell_min_mv) on 20 of 36 samples and differed by
        // exactly 1 mV on the rest - the skew between two batched reads.
        f.add(new Field("34D5", "cell_delta_bms_mv", "BMS delta", "mV", Kind.U16_RAW, false));
        f.add(new Field("347B", "chg_limit_a",  "Charge limit", "A", Kind.U16_DIV10, false));
        f.add(new Field("347D", "out_cont_kw",  "Out cont", "kW", Kind.U16_DIV10,   false));
        f.add(new Field("347E", "out_peak_kw",  "Out peak", "kW", Kind.U16_DIV10,   false));
        f.add(new Field("3480", "regen_cont_kw", "Regen cont", "kW", Kind.U16_DIV10, false));
        // Main relays negative / positive / pre-charge; 0x06 while driving.
        f.add(new Field("3404", "relay_bits",   "Relays",   "",   Kind.U8_RAW,      false));
        f.add(new Field("340A", "temp_max_probe", "Hot probe", "", Kind.U8_RAW,    false));
        f.add(new Field("340C", "temp_min_probe", "Cold probe", "", Kind.U8_RAW,   false));
        // Inverted in the catalog: 0 = connected, 1 = disconnected.
        f.add(new Field("341C", "charge_port",  "Charge port", "", Kind.U8_RAW,    false));
        f.add(new Field("347A", "oper_mode",    "BMS mode", "",   Kind.U8_RAW,      false));
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
     * Override value meaning "this role is not read on this car at all".
     *
     * Clearing an override restores a DEFAULT, which is not the same thing: on a
     * model where some other reading lives at a role's default DID, that role has
     * to be able to step aside, or the reading that wants the code can never be
     * mapped. Not a valid DID (a DID is four hex digits), so it cannot collide
     * with one, and it is an ordinary override value - so Scan's "Reset to
     * default DIDs and scales" clears it like any other.
     */
    static final String DISABLED = "-";

    static boolean isDisabled(String did) {
        return did != null && DISABLED.equals(did.trim());
    }

    /**
     * The DID this field should actually be read from: a user override if one is
     * set for this vehicle, otherwise the default discovered on the Nexon EV Max.
     *
     * NULL when the role is switched off for this vehicle. Every caller has to
     * mean something by that: the poll does not ask for it, the CSV header names
     * the column "none", and the dashboard caption reads "off".
     */
    static String effectiveDid(Field f, Prefs prefs) {
        if (prefs == null) return f.did;
        String o = prefs.didOverride(f.key);
        if (isDisabled(o)) return null;
        return (o == null || o.isEmpty()) ? f.did : o;
    }

    /** Look up a field by its role key, or null. */
    static Field byKey(String key) {
        if (key == null) return null;
        for (Field f : ALL) if (f.key.equals(key)) return f;
        return null;
    }

    /**
     * A per-role decoding override for a BMS whose encoding differs from the
     * Gotion map: value = raw x factor + offset, over {@code width} unsigned
     * big-endian bytes. Stored per profile as "factor;offset;width". Volt roles
     * keep their units: a 10 mV controller's cell voltage gets factor 10, so
     * the role still reads millivolts everywhere downstream.
     */
    static final class Scale {
        final double factor;
        final double offset;
        final int width;

        Scale(double factor, double offset, int width) {
            this.factor = factor;
            this.offset = offset;
            this.width = width;
        }

        static Scale parse(String s) {
            if (s == null || s.trim().isEmpty()) return null;
            String[] p = s.split(";");
            if (p.length != 3) return null;
            try {
                double f = Double.parseDouble(p[0].trim());
                double o = Double.parseDouble(p[1].trim());
                int w = Integer.parseInt(p[2].trim());
                if (f == 0 || Double.isNaN(f) || Double.isNaN(o) || w < 1 || w > 4) return null;
                return new Scale(f, o, w);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        String encode() {
            return plain(factor) + ";" + plain(offset) + ";" + width;
        }

        private static String plain(double v) {
            if (v == Math.rint(v)) return String.valueOf((long) v);
            return String.format(Locale.ROOT, "%s", v);
        }
    }

    /** The override in force for this role on this vehicle, or null. */
    static Scale scaleOf(Field f, Prefs prefs) {
        if (prefs == null) return null;
        return Scale.parse(prefs.scaleOverride(f.key));
    }

    /** Payload width in bytes as this vehicle serves it. */
    static int width(Field f, Prefs prefs) {
        Scale s = scaleOf(f, prefs);
        return s == null ? width(f.kind) : s.width;
    }

    /**
     * Decode with an optional override. Current never takes one: its zero and
     * scale live in the calibration the charger routine already adjusts.
     */
    static Double decode(Field f, byte[] data, Scale s, double currentScale, int currentZero) {
        if (s == null) return decode(f, data, currentScale, currentZero);
        if (data == null || data.length != s.width) return null;
        long raw = 0;
        for (byte b : data) raw = (raw << 8) | (b & 0xFF);
        if (f.kind == Kind.CURRENT_MID) return (raw - currentZero) * currentScale;
        return raw * s.factor + s.offset;
    }

    static List<Field> primary() {
        List<Field> out = new ArrayList<>();
        for (Field x : ALL) if (x.primary) out.add(x);
        return out;
    }

    /** Fields for the dashboard's status strip, in strip order. */
    static List<Field> status() {
        List<Field> out = new ArrayList<>();
        for (Field x : ALL) if (x.status) out.add(x);
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
