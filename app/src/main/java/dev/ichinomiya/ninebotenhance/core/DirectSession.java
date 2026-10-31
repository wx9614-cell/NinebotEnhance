package dev.ichinomiya.ninebotenhance.core;

import dev.ichinomiya.ninebotenhance.ipc.Protocol;

/**
 * Two layers. The virtual display is started on its own or by the first cast and lives until it is closed or the host exits; a
 * cast binds that display to the vehicle and lets go again without touching it. Vehicle checks precede the binding (and any
 * display created for it); stale callbacks cannot take ownership of either layer.
 */
public final class DirectSession {
    public enum Display { IDLE, STARTING, READY }
    public enum Cast { IDLE, CHECKING_VEHICLE, WAITING_DISPLAY, STARTING, RUNNING }
    private volatile Display display = Display.IDLE;
    private volatile String displayRequest;
    private volatile Cast cast = Cast.IDLE;
    private volatile String castRequest;
    private Boolean vehiclePower;
    private boolean cruiseReady;
    private String phase = "IDLE";
    // ---------------------------------------------------------------- display layer
    public synchronized boolean beginDisplay(String id) {
        if (display != Display.IDLE || !Protocol.validRequest(id)) return false;
        displayRequest = id; display = Display.STARTING; return true;
    }
    public boolean displayReady(String id) {
        if (!ownsDisplay(id) || display != Display.STARTING) return false;
        display = Display.READY; return true;
    }
    /** Closing the display also drops any cast bound to it. */
    public synchronized boolean endDisplay(String id) {
        if (!ownsDisplay(id)) return false;
        displayRequest = null; display = Display.IDLE; castRequest = null; cast = Cast.IDLE; phase = "STOPPED"; return true;
    }
    public boolean ownsDisplay(String id) { String current = displayRequest; return current != null && current.equals(id); }
    public String displayRequest() { return displayRequest; }
    public Display display() { return display; }
    public boolean displayRunning() { return display != Display.IDLE; }
    // ---------------------------------------------------------------- cast layer
    public synchronized boolean beginCast(String id) {
        if (cast != Cast.IDLE || !Protocol.validRequest(id)) return false;
        castRequest = id; vehiclePower = null; cruiseReady = false; phase = "CHECKING_VEHICLE"; cast = Cast.CHECKING_VEHICLE; return true;
    }
    public boolean ownsCast(String id) { String current = castRequest; return current != null && current.equals(id); }
    /** Atomic phase/request snapshot for a query entering on a non-UI thread. */
    public synchronized String vehicleCheckRequest() { return cast == Cast.CHECKING_VEHICLE ? castRequest : null; }
    public boolean powerChecked(String id, boolean on) {
        if (!ownsCast(id) || cast != Cast.CHECKING_VEHICLE) return false;
        // An observed rejection cannot be superseded by another concurrent query.
        if (!Boolean.FALSE.equals(vehiclePower)) vehiclePower = on;
        phase = on ? "VEHICLE_POWER_ON" : "VEHICLE_POWER_OFF";
        return true;
    }
    public boolean cruiseReady(String id) {
        if (!ownsCast(id) || cast != Cast.CHECKING_VEHICLE) return false;
        cruiseReady = true; phase = "CRUISE_READY"; return true;
    }
    /** Both prerequisites observed for this attempt: the cast may take, or start, the display. Succeeds exactly once. */
    public boolean vehicleConfirmed(String id) {
        if (!ownsCast(id) || cast != Cast.CHECKING_VEHICLE || !Boolean.TRUE.equals(vehiclePower) || !cruiseReady) return false;
        cast = Cast.WAITING_DISPLAY; phase = "WAITING_DISPLAY"; return true;
    }
    /** The display is ready and its frames start going to the encoder. */
    public boolean launch(String id) {
        if (!ownsCast(id) || cast != Cast.WAITING_DISPLAY || display != Display.READY) return false;
        cast = Cast.STARTING; phase = "CAST_STARTING"; return true;
    }
    public boolean running(String id) {
        if (!ownsCast(id) || cast != Cast.STARTING) return false;
        cast = Cast.RUNNING; phase = "RUNNING"; return true;
    }
    /** The cast lets go of the display, which stays as it is. */
    public synchronized boolean endCast(String id) {
        if (!ownsCast(id)) return false;
        castRequest = null; cast = Cast.IDLE; phase = "STOPPED"; return true;
    }
    public String castRequest() { return castRequest; }
    public Cast cast() { return cast; }
    public synchronized String phase() { return phase; }
    public boolean casting() { return cast != Cast.IDLE; }
}
