# Smart Island — Bluetooth toggle, reverse-exit animation & overlay touch notes

This document records the decisions and constraints behind three long-running
issues: the Bluetooth toggle in the idle info menu, the reverse
"app shrinks back into the island" animation, and overlay touch passthrough,
plus the Quick Settings Bluetooth detail panel question.
Target platform reference: Android 16/17 (YAAP 17 custom ROM).

---

## 1. Bluetooth toggle in the idle info menu (info menu never hides/closes)

### Why `BluetoothAdapter.enable()/disable()` cannot work

Since Android 12, `BluetoothAdapter.enable()` and `BluetoothAdapter.disable()`
are callable only by apps holding `BLUETOOTH_PRIVILEGED` (system/signature
level). A normal app gets `false` even with `BLUETOOTH_CONNECT` granted, and
there is no consent dialog for the disable direction. `Settings.Panel`
(`ACTION_BLUETOOTH`) is a system dialog and requires user interaction —
rejected because the info menu must never be covered by dialogs.

### Implemented solution — Shizuku shell path (preferred)

The device runs Shizuku with the `moe.shizuku.manager.permission.API_V23`
permission granted to Smart Island. Shell (`shell` uid) holds
`BLUETOOTH_PRIVILEGED`, so the toggle is dispatched through the hidden
BluetoothManagerService shell commands:

1. `cmd bluetooth_manager enable|disable` (Android 11+ shell command)
2. `svc bluetooth enable|disable` (older entry point, still present on
   current ROMs)

Chained with `||` in `ShizukuManager.toggleBluetooth()` so the second is
tried when the first is unavailable. No dialogs, no settings pages, no shade
pull-down — the overlay window is never touched, the island stays visible,
and the info menu stays open.

Verification is permission-free: the state is polled via
`Settings.Global "bluetooth_on"` (readable without any Bluetooth permission)
for up to 4 s. Because the test device suppresses Toasts for this app, the
result ("Turning Bluetooth on…", "Bluetooth on", "Couldn't toggle
Bluetooth") is rendered inside the island menu itself (`IslandViewModel
.postMenuFeedback` → `IdleInfoExpanded`).

### Fallback — Quick Settings tile gesture

If Shizuku is not running (`isBinderAvailable()` false) or the command chain
fails, the accessibility service falls back to the QS tile flow:

1. `GLOBAL_ACTION_QUICK_SETTINGS` opens the panel.
2. `waitForQuickSettings()` waits (≤ 2.5 s) for the full-screen SystemUI
   window (same window-bounds detection the shade-hide feature uses).
3. `findBluetoothTileNode()` searches `rootInActiveWindow` **and** all
   `TYPE_SYSTEM` windows for the tile (layered matching: exact
   contentDescription → containing description → text; tile-sized bounds
   required). If not found, the tile carousel is swiped (≤ 2 pages).
4. A synthetic gesture taps the tile centre; the state change is verified
   (≤ 2 s) and the tap is retried until a 6 s deadline.
5. QS is closed with `GLOBAL_ACTION_BACK` — only if QS was actually opened,
   so a failed open never sends a stray BACK to the launcher.

During the whole flow `suppressShadeHide = true` keeps the island visible and
the window carries `FLAG_NOT_TOUCHABLE` so the gesture reaches the panel
below. With the content-sized expanded window (§3) this is now only needed
while QS is actually open.

---

## 2. Reverse "app shrinks back into the island" animation

**Conclusion: a true reverse transition is impossible for a third-party app
on Android 16/17.** Rationale:

- `ActivityOptions.makeScaleUpAnimation` (used for the open animation) only
  styles the *incoming* activity's launch animation. There is no symmetric
  API: the *exit* animation of a task/window is chosen by the system
  (SystemUI/WM shell) and by the departing app itself, not by third parties.
- Shell transitions (`TransitionController`, `WindowManagerService`) are
  system-internal; remote transitions are only delegated to the app that owns
  the participating windows.
- `ActivityEmbedding` / shared-element transitions operate only within an
  app's own task; they cannot intercept another app's exit.
- Per-window `WindowAnimation`/`enterTransition` attributes affect the
  animating window only, and `TaskView`/`VirtualDeviceManager` embedding
  requires system privileges.

**Implemented mitigation — the reappear illusion.** The service detects when
the launcher returns to the foreground (window-state events, resolved via
`CATEGORY_HOME`) and when the overlay returns from `GONE` to `VISIBLE`
(app closed, shade closed, unlock). Each occurrence bumps
`IslandViewModel.reappearTick`, and the pill replays a spring scale-in
(0.55 → 1.0) from the punch-hole position. The app's own exit animation plays
unmodified, but the island visibly "catches" the app when you come back — the
closest supported approximation of the reverse shrink.

---

## 3. Overlay touch passthrough without `touchableRegion`

The hidden `ViewTreeObserver$InternalInsetsInfo.touchableRegion` reflection
is blocked on the target Android version, and there is **no public
WindowManager API for a per-window touchable region** (`FLAG_NOT_TOUCH_MODAL`
only passes touches outside the window frame; `TouchDelegate` cannot extend
beyond the window). The supported pattern is therefore window geometry:

- **Collapsed**: unchanged narrow-window fallback (sized to the pill group,
  `FLAG_NOT_TOUCH_MODAL`).
- **Expanded** (new): when the touchableRegion API is unavailable, the window
  is sized to the measured island content instead of `MATCH_PARENT`. The
  Compose tree reports the content size (ceiling-quantized to an 8 dp grid to
  avoid per-frame window relayouts during page swipes) via
  `onExpandedWindowContentSize` → `updateWindowLayoutParams`.
- **Dismissal**: `FLAG_WATCH_OUTSIDE_TOUCH` converts taps anywhere outside
  the window into `ACTION_OUTSIDE` events, which collapse the expanded
  island — preserving the old full-screen "tap outside to dismiss" behaviour
  without swallowing touches.
- Devices where the reflection still works keep the previous full-screen
  window + insets-region behaviour untouched.

This is what removes the need for flag gymnastics during in-menu toggles:
outside the island's card, all touches now reach the underlying app/system UI
by construction.

---

## 4. Music card seek bar vs pager gesture arbitration

`WavyMusicSeekBar` previously consumed every drag (`detectDragGestures`),
which hijacked horizontal swipes across the card and made the info page hard
to reach while music played. New arbitration:

- **Tap** the bar → seek to the tapped position.
- **Press-and-hold** (~220 ms, haptic tick) **then drag** → deliberate scrub;
  the gesture is claimed so the pager cannot steal it mid-drag.
- **Any quick drag before the hold elapses** → *not* consumed: the pager
  pages, and the pill's configured swipe-up/down gestures keep working.

## 5. QS Bluetooth detail panel

The detail panel the Quick Settings tile opens on long-press is hosted by
SystemUI (`com.android.systemui` internal activity, not exported, guarded by
system permissions). The only public entry point remains
`Settings.ACTION_BLUETOOTH_SETTINGS` (the full Bluetooth settings page).
**Left as-is by design**: with the Shizuku toggle implemented, opening any
Bluetooth page is no longer needed for toggling.

---

## Device test checklist (Android 17 / YAAP)

1. `./gradlew assembleDebug testDebugUnitTest && adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Bluetooth: `adb logcat -s SmartIslandOverlayService` while tapping the
   Bluetooth row — expect `Shizuku bluetooth toggle: dispatched=true
   changed=true`; verify the row flips to "On"/"Off" and the menu stays open.
3. Toggle with Shizuku stopped (`adb shell sh /storage/emulated/0/stop_shizuku.sh`
   or via Shizuku app) to exercise the QS fallback: island must stay visible,
   QS opens/closes automatically, state flips both directions.
4. Expanded island: touch the area *below* the card — the underlying app must
   receive the touch; tapping outside the card must collapse the island.
5. Music card: quick horizontal swipes on the seek bar must page; tap on the
   bar must seek; press-hold-drag must scrub.
6. Open an app from the island, then go home: the island replays its
   scale-in "reappear" animation.
