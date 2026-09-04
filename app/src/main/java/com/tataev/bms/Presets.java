package com.tataev.bms;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The second way a Tata BMS lays out the same quantities.
 *
 * The Gotion controller in the Nexon EV Max serves the $34xx block this app was
 * built on. Three other controllers in Tata's service-tool catalogs - TacoGotion,
 * CESL and Kratos - serve one $30xx block with the same roles at different DIDs
 * and, per supplier, different scales: cell volts in 10 mV or 1 mV, SOC in two
 * bytes of tenths or one byte of halves, temperatures offset by 50 or 40. The
 * three are told apart by the widths of two replies a detection sweep already
 * sees. Roles a controller does not serve are simply absent and read "--".
 *
 * Current is not given a Scale: each preset sets the zero and per-count scale
 * the existing calibration uses, so the charger routine still refines it.
 * Pure Java, so every map is checked for legality by the self-test.
 */
final class Presets {

    private Presets() { }

    static final class Preset {
        final String name;
        /** role key -> DID */
        final Map<String, String> dids;
        /** role key -> "factor;offset;width" */
        final Map<String, String> scales;
        final float currentScale;
        final int currentZero;

        Preset(String name, Map<String, String> dids, Map<String, String> scales,
               float currentScale, int currentZero) {
            this.name = name;
            this.dids = Collections.unmodifiableMap(dids);
            this.scales = Collections.unmodifiableMap(scales);
            this.currentScale = currentScale;
            this.currentZero = currentZero;
        }
    }

    private static Map<String, String> m(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
        return out;
    }

    private static Map<String, String> plus(Map<String, String> base, String... kv) {
        Map<String, String> out = new LinkedHashMap<>(base);
        out.putAll(m(kv));
        return out;
    }

    /** The $30xx role map every supplier shares. */
    private static final Map<String, String> COMMON_DIDS = m(
            "soc_pct", "300F", "soh_pct", "3010", "pack_v", "300D", "current_a", "300E",
            "cell_max_mv", "3017", "cell_min_mv", "3018", "cell_max_idx", "3019",
            "cell_min_idx", "301A", "temp_a_c", "3011", "temp_b_c", "3012",
            "insulation_kohm", "3016", "aux_12v_v", "3024", "dis_limit_a", "3020",
            "fault_rank", "3013");

    /** Tata AutoComp Gotion: 10 mV cells, 2-byte SOC in tenths, temperatures -50. */
    static final Preset TACO_GOTION = new Preset("TacoGotion 30xx",
            plus(COMMON_DIDS, "chg_limit_a", "301F", "coolant_in_c", "3014", "temp_c_c", "3015",
                    "temp_d_c", "3054", "cell_delta_bms_mv", "305B", "out_cont_kw", "305C",
                    "out_peak_kw", "305D", "regen_peak_kw", "305E", "regen_cont_kw", "305F"),
            m("soc_pct", "0.1;0;2", "soh_pct", "1;0;1", "pack_v", "0.1;0;2",
                    "cell_max_mv", "10;0;2", "cell_min_mv", "10;0;2",
                    "cell_max_idx", "1;0;1", "cell_min_idx", "1;0;1",
                    "temp_a_c", "1;-50;1", "temp_b_c", "1;-50;1", "coolant_in_c", "1;-50;1",
                    "temp_c_c", "1;-50;1", "temp_d_c", "1;-50;1",
                    "insulation_kohm", "1;0;2", "aux_12v_v", "0.01;0;2",
                    "dis_limit_a", "0.1;0;2", "chg_limit_a", "0.1;0;2", "fault_rank", "1;0;1",
                    "cell_delta_bms_mv", "1;0;2", "out_cont_kw", "0.1;0;2", "out_peak_kw", "0.1;0;2",
                    "regen_peak_kw", "0.1;0;2", "regen_cont_kw", "0.1;0;2"),
            0.1f, 6000);

    /** CESL: 1 mV cells, 1-byte SOC in halves, temperatures x0.5 -40, insulation in ohm. */
    static final Preset CESL = new Preset("CESL 30xx",
            plus(COMMON_DIDS, "chg_limit_a", "301F", "coolant_in_c", "3014", "temp_c_c", "3015",
                    "link_v", "3030"),
            m("soc_pct", "0.5;0;1", "soh_pct", "0.5;0;1", "pack_v", "0.1;0;2",
                    "cell_max_mv", "1;0;2", "cell_min_mv", "1;0;2",
                    "cell_max_idx", "1;0;1", "cell_min_idx", "1;0;1",
                    "temp_a_c", "0.5;-40;1", "temp_b_c", "0.5;-40;1",
                    "coolant_in_c", "1;-40;1", "temp_c_c", "1;-40;1",
                    "insulation_kohm", "0.001;0;2", "aux_12v_v", "0.1;0;2",
                    "dis_limit_a", "1;0;1", "chg_limit_a", "1;0;1", "fault_rank", "1;0;1",
                    "link_v", "0.1;0;2"),
            0.5f, 2000);

    /** Kratos: 1 mV cells, 1-byte SOC in halves, 2-byte cell numbers, pack volts x0.25. */
    static final Preset KRATOS = new Preset("Kratos 30xx",
            plus(COMMON_DIDS, "coolant_in_c", "306B", "temp_c_c", "306C", "link_v", "3030"),
            m("soc_pct", "0.5;0;1", "soh_pct", "0.5;0;1", "pack_v", "0.25;0;2",
                    "cell_max_mv", "1;0;2", "cell_min_mv", "1;0;2",
                    "cell_max_idx", "1;0;2", "cell_min_idx", "1;0;2",
                    "temp_a_c", "1;-40;1", "temp_b_c", "1;-40;1",
                    "coolant_in_c", "1;-40;1", "temp_c_c", "1;-40;1",
                    "insulation_kohm", "1;0;2", "aux_12v_v", "0.1;0;2",
                    "dis_limit_a", "0.25;0;2", "fault_rank", "1;0;1",
                    "link_v", "0.02;0;2"),
            0.5f, 2000);

    static List<Preset> all() {
        return Collections.unmodifiableList(Arrays.asList(TACO_GOTION, CESL, KRATOS));
    }

    /**
     * Which $30xx controller answered, from two reply widths: SOC (300F) is two
     * bytes only on TacoGotion; the cell number (3019) is two bytes only on
     * Kratos. Null when SOC did not answer at all.
     */
    static Preset identify30xx(int socLen, int idxLen) {
        if (socLen < 1) return null;
        if (socLen == 2) return TACO_GOTION;
        return idxLen == 2 ? KRATOS : CESL;
    }
}
