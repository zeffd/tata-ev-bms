package com.tataev.bms;

import java.util.List;

/**
 * What the poll actually sent and got back when the dashboard could not decode
 * anything: the evidence a screenshot cannot carry. Written by the service on
 * its two "nothing decoded" exits and offered from the dashboard beside the
 * failure. Never a VIN: the adapter's transcript skips the VIN read,
 * and this scrubs any 17-character run that slipped through regardless.
 */
final class PollReport {

    private PollReport() { }

    static String render(String adapterHeader, String appVersion, String protocol, String target,
                         boolean batching, List<String> resolvedRoles,
                         String traffic, String reason, String vinRead, String session) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tata EV BMS - poll report\n");
        sb.append("The link was up but no reading could be decoded. "
                + "Below is what was asked and what came back.\n\n");
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
}
