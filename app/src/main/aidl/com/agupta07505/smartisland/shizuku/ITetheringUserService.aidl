/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.shizuku;

/**
 * UserService interface executed inside the Shizuku server process (uid 2000,
 * "shell"). TetheringService.startTethering/stopTethering enforce
 * TETHER_PRIVILEGED — a signature|privileged permission the shell uid holds
 * (requested by the platform Shell app's manifest) and a normal app does not.
 * Calling TetheringManager from this process is therefore the only reliable
 * way to toggle the Wi-Fi hotspot / Bluetooth tethering with the user's SAVED
 * configuration; the old `cmd connectivity tethering` shell command does not
 * exist in AOSP at all.
 *
 * All methods are synchronous binder calls into system_server and MUST do
 * their own timeout policing (see the implementation).
 */
interface ITetheringUserService {

    /**
     * Starts or stops the Wi-Fi hotspot through TetheringManager.
     *
     * @param type TetheringManager.TETHERING_WIFI (0). The USB and Bluetooth
     *             tethering rows no longer exist; any other value is rejected
     *             with the local ERR_UNAVAILABLE code.
     * @param enable true to start, false to stop.
     * @return 0 (TETHER_ERROR_NO_ERROR) on confirmed success; the platform's
     *         TETHER_ERROR_* code when the platform rejected the request;
     *         negative SmartIsland-side codes (-1 unavailable, -2 timeout,
     *         -3 state did not change) on local failures.
     */
    int setTethering(int type, boolean enable);

    /**
     * Toggles the Bluetooth radio from the shell-uid process.
     *
     * Since Android 12, BluetoothAdapter.enable()/disable() return false for
     * normal apps, but the shell uid holds BLUETOOTH_PRIVILEGED (the platform
     * Shell app requests it and the permission is privapp-whitelisted for
     * com.android.shell), so the adapter call is legitimate from here. This
     * is the same mechanism `svc bluetooth enable|disable` uses internally —
     * dispatched over a stable binder method instead of a spawned shell, so
     * it cannot be broken by shell-command changes.
     *
     * @param enable true to turn Bluetooth on, false to turn it off.
     * @return true when the adapter accepted the request OR the radio is
     *         already in the requested state; false when the adapter refused
     *         or the read failed (caller falls back to the shell commands).
     */
    boolean setBluetoothEnabled(boolean enable);

    /**
     * The platform's live tethered-interface list (TetheringManager
     * .getTetheredIfaces()) read from the shell-uid process, where the
     * hidden-API reflection block that silences this read inside the app
     * process does not apply. Pipe-separated interface names; empty string
     * when the read is unavailable. Used to verify that a toggle actually
     * changed the tethering state (HotspotUtil's in-process readers are
     * reflection-blocked on modern Android and return null/unknown).
     *
     * @return pipe-separated lowercase interface names, e.g. "ap0|wlan1",
     *         or "" when the platform read is unavailable.
     */
    String getTetheredIfaces();

    /** Shuts the user service process down (standard Shizuku contract). */
    void exit();
}
