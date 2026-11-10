package com.tataev.bms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the poll actually sent and got back when the dashboard could not decode
 * anything: the evidence a screenshot cannot carry. Written by the service when
 * a connect never reached the controller and when a link's polls decoded
 * nothing, and offered from the dashboard beside the failure. Never a VIN: the
 * adapter's transcript skips the VIN read, and this scrubs any VIN that slipped
 * through regardless - plain, or hex-encoded within one line. (A VIN split
 * across multi-frame lines is not decoded here; skipping the read is what keeps
 * that one out.)
 */
final class PollReport {

    private PollReport() { }

    /**
     * @param linkUp whether a link to the controller was established; a connect
     *               that never reached it must not open with "the link was up"
     */
    static String render(String adapterHeader, String appVersion, String protocol, String target,
                         boolean batching, List<String> resolvedRoles,
                         String traffic, String reason, String vinRead, String session,
                         boolean linkUp) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tata EV BMS - poll report\n");
        sb.append(linkUp ? "The link was up but no reading could be decoded. "
                : "The connection never reached the battery controller. ");
        sb.append("Below is what was asked and what came back.\n\n");
        sb.append(adapterHeader == null ? "" : adapterHeader);
        sb.append("App: ").append(appVersion == null ? "" : appVersion).append('\n');
        sb.append("Protocol: ").append(protocol == null ? "" : protocol).append('\n');
        sb.append("Target: ").append(target == null ? "" : target).append('\n');
        sb.append("Batching: ").append(batching ? "on" : "off").append('\n');
        sb.append("Reason: ").append(reason == null ? "" : reason).append('\n');
        // How the identification read and the session request went - the OUTCOMES,
        // never the VIN itself. A car that answers neither is a different problem
        // from one that answers both and still decodes nothing.
        sb.append("VIN read: ").append(vinRead == null || vinRead.isEmpty()
                ? "(not attempted)" : vinRead).append('\n');
        sb.append("Session: ").append(session == null || session.isEmpty()
                ? "(unknown)" : session).append("\n\n");
        sb.append("Roles asked (role=DID):\n");
        if (resolvedRoles != null) {
            for (String r : resolvedRoles) sb.append("  ").append(r).append('\n');
        }
        sb.append("\nRecent traffic (newest last):\n");
        sb.append(Redact.withoutVin(traffic == null ? "" : traffic));
        if (sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        return sb.toString();
    }

    /** Poll reports kept; a link that keeps failing writes one every few seconds. */
    static final int KEEP = 10;

    /**
     * The poll reports to delete so only the newest {@link #KEEP} remain, oldest
     * first. Only {@code poll_*.txt}: the drive logs share the directory. The
     * timestamp in the name is fixed width, so name order is age order.
     *
     * @param names a directory listing; null (an unreadable directory) deletes nothing
     */
    static List<String> stale(String[] names) {
        List<String> polls = new ArrayList<>();
        if (names != null) {
            for (String n : names) {
                if (n != null && n.startsWith("poll_") && n.endsWith(".txt")) polls.add(n);
            }
        }
        if (polls.size() <= KEEP) return new ArrayList<>();
        Collections.sort(polls);
        return new ArrayList<>(polls.subList(0, polls.size() - KEEP));
    }
}
