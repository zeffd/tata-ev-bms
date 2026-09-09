package com.tataev.bms;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the adapter said about itself while being set up: its reset banner and
 * every AT command it answered "?" to.
 *
 * The first "works on your car, not on mine" report was the same model with a
 * different dongle. A clone that rejects the receive-filter or flow-control
 * commands can make a healthy car read as silent, and nothing in the shared
 * evidence used to say so. This record goes at the top of the poll report, so
 * support starts from the adapter rather than the car. Pure Java; ElmClient
 * feeds it, nothing here touches the wire.
 */
final class AdapterCaps {

    /** Commands a genuine ELM327 (v1.3 or later) always accepts. */
    private static final String[] EXPECTED = {
            "ATE0", "ATL0", "ATS0", "ATH1", "ATCAF1", "ATSP6", "ATAT1",
            "ATSH", "ATCRA", "ATFCSH", "ATFCSD300000", "ATFCSM1"
    };

    private String banner = "";
    private final List<String> rejected = new ArrayList<>();
    private final List<String> accepted = new ArrayList<>();

    void reset() {
        banner = "";
        rejected.clear();
        accepted.clear();
    }

    /** The ATZ reply: the line naming a chip if there is one, else the first line. */
    void noteBanner(String atzReply) {
        if (atzReply == null) return;
        String first = "";
        for (String line : atzReply.replace('\r', '\n').split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.equals("?") || t.equalsIgnoreCase("OK")) continue;
            if (first.isEmpty()) first = t;
            String up = t.toUpperCase(Locale.ROOT);
            if (up.contains("ELM") || up.contains("OBD")) {
                banner = t;
                return;
            }
        }
        banner = first;
    }

    /** Record one AT command's outcome. A bare "?" line is the adapter refusing it. */
    void note(String cmd, String reply) {
        String key = family(cmd);
        List<String> list = UdsCodec.isAdapterReject(reply) ? rejected : accepted;
        if (!list.contains(key)) list.add(key);
    }

    /**
     * "ATSH785" and "ATSH 785" are one capability, ATSH; so are ATCRA with and
     * without an id. Protocol selections stay distinct: an adapter can know one
     * protocol-select command and refuse another.
     */
    static String family(String cmd) {
        String c = cmd == null ? "" : cmd.replace(" ", "").toUpperCase(Locale.ROOT);
        // ATCRA before ATCM and ATCF: none of them is a prefix of another,
        // but the order makes that independent of how the array is written.
        for (String p : new String[]{"ATSH", "ATCRA", "ATFCSH", "ATCM", "ATCF"}) {
            if (c.startsWith(p)) return p;
        }
        return c;
    }

    boolean supports(String cmd) {
        return !rejected.contains(family(cmd));
    }

    String banner() {
        return banner;
    }

    List<String> rejected() {
        return new ArrayList<>(rejected);
    }

    /** A dongle refusing something every real ELM327 accepts. */
    boolean looksLikeClone() {
        for (String r : rejected) {
            for (String e : EXPECTED) if (r.equals(e)) return true;
        }
        return false;
    }

    /** The header both shareable reports carry. ASCII: it goes through share sheets. */
    String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("Adapter: ").append(banner.isEmpty() ? "(no banner)" : banner).append('\n');
        if (rejected.isEmpty()) {
            sb.append("Adapter rejected: none\n");
            return sb.toString();
        }
        // Not String.join: that is API 26 and minSdk is 24 (and this class is pure
        // Java anyway, so android.text.TextUtils is not available here either).
        StringBuilder list = new StringBuilder();
        for (String r : rejected) {
            if (list.length() > 0) list.append(", ");
            list.append(r);
        }
        sb.append("Adapter rejected: ").append(list).append('\n');
        if (!supports("ATCRA")) {
            sb.append("  no receive filter: replies are filtered in software\n");
        }
        if (!supports("ATFCSH") || !supports("ATFCSD300000") || !supports("ATFCSM1")) {
            sb.append("  no flow control: multi-frame replies may arrive truncated\n");
        }
        if (looksLikeClone()) sb.append("  this looks like a clone ELM327\n");
        return sb.toString();
    }
}
