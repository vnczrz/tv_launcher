# Vox spikes: findings

Throwaway build of `tv.voxclean.launcher` (label "Vox"): one HOME activity with YouTube and Netflix buttons,
and one `DreamService` that logs what UsageStats reports. Run on a Chromecast with Google TV 4K (sabrina),
Google TV 14, build UTTC.250917.004, with upstream's `com.gathin.tvhome` installed and its `HomeKeyService`
enabled (`app_keys` 190 to YouTube, 191 to Netflix). The spike is uninstalled and the device is back on
upstream's Home.

Build: `./launcher/gradlew -p spikes/vox :app:assembleDebug` (same AGP and SDK as `launcher/`).

## a. Key helper: upstream's HomeKeyService still works with Vox as Home

**Observed.** With Vox as Home (confirmed by `mFocusedApp`), the owner pressed the real remote's YouTube
button and then its Netflix button: YouTube opened, then Netflix opened.

**How established.** Real remote, not injected keys. While the owner pressed, a poll of `mFocusedApp`
caught `com.netflix.ninja` coming to the front and Vox returning after it. The YouTube press fell between
two polls, but `dumpsys usagestats` afterwards listed `com.google.android.youtube.tv` with a last-used time
a few seconds before the Netflix one.

**Why it works (read in `launcher/.../HomeKeyService.java`).** It is an accessibility service that handles
`app_keys` in `onKeyEvent` and starts the app itself. It does not depend on its own package being Home.

**Not tested.** Boot (the service waits for user unlock), long press, Volume keys, and a reinstall of
upstream (the spec says the service must be re-enabled afterwards).

**Recommendation.** Do not copy the key helper into Vox. Keep upstream installed and its service enabled,
and say so in the install notes. Bring a trimmed copy in only if upstream is ever removed.

## b. Last app: UsageStats works, but only with a query window that reaches past "now"

**Observed.** With `appops set tv.voxclean.launcher GET_USAGE_STATS allow` (confirmed with `appops get`),
a DreamService in Vox saw no events through the obvious calls:

- `queryEvents(now - 10 min, now)` returned 0 events, in every run.
- `queryUsageStats(INTERVAL_DAILY, now - 24 h, now)` returned an empty list.

`dumpsys usagestats` showed the data was there, but filed in a daily bucket whose time range starts on
9/9/2027, with the "Last 24 hour events" list empty. Asking for `queryEvents(now - 10 min, now + 2 years)`
returned the events (61 and 72 in the two runs), and `queryUsageStats(INTERVAL_BEST, 0, now + 2 years)`
returned 55 packages.

**Inferred, not proven.** The device clock was wrong earlier (a 2027 date shows up in other
package timestamps) and the bucket kept that date, so a query ending at the real "now" skips it. Unknown:
whether a reboot or a day change cleans this up.

**What the wide query reports** (dream started by idle, over each screen):

| In front when the dream started | Events just before the dream's own resume |
|---|---|
| Netflix | Netflix PAUSED 114 ms before; the dream (Vox package) RESUMED 94 ms before |
| Vox Home | Vox Home PAUSED 139 ms before; the dream RESUMED 120 ms before |

So the app that was in front is the package of the last `ACTIVITY_RESUMED` before the dream's own one.
Taking "the last RESUMED event that is not ours" is wrong: in the Vox case it reports Netflix, which was
open minutes earlier.

**How the dream was started.** `cmd dreams start-dreaming` is refused (`Must be root to call Dream shell
commands`) and `com.android.systemui/.Somnambulator` does not exist on this build. Instead:
`settings put system screen_off_timeout 15000`, then leave the screen idle for about 15 to 25 s. Both settings
were put back (`600000` and `com.gathin.tvhome/.ClockDream`). On one run the TV went to sleep instead of
dreaming; waking it and waiting again gave a dream.

**Recommendation.** Use the wide window (`end = now + 2 years`) and filter by timestamp yourself. Walk the
events backwards, skip the dream's own resume (inferred: its class is `android.service.dreams.DreamActivity`,
seen in `mFocusedApp`; the final spike logged packages only, not classes), and take the next
`ACTIVITY_RESUMED`. If that is Vox's own package, use the time-of-day lines. Keep the spec's fallback (the
launcher's own record of the last app it opened) for when the list comes back empty.

## c. Handover and rollback work; the couch fallback lists both launchers

**Observed.**

1. Vox started with `am start -W`: `Status: ok`.
2. `cmd package set-home-activity tv.voxclean.launcher/.MainActivity` printed `Success`. After HOME,
   `mFocusedApp` was Vox, and the HOME resolver returned Vox.
3. `am start -a android.settings.HOME_SETTINGS` opened the permission controller's "Default home app"
   screen, listing two choices: "Home" (upstream) and "Vox" (selected). The resolver lists that activity plus
   `com.android.tv.settings/.EmptyStubActivity`.
4. `cmd package set-home-activity com.gathin.tvhome/.MainActivity` printed `Success`. After HOME,
   `mFocusedApp` was `com.gathin.tvhome/.MainActivity` and the resolver agreed.
5. `pm uninstall tv.voxclean.launcher` succeeded; Home stayed on upstream.

**Recommendation.** `set-home-activity` works as the spec describes; keep confirming with `mFocusedApp`
after HOME rather than trusting `Success`. Vox's launcher label is what shows in the couch-side chooser, so
"Vox" is fine. The chooser shows upstream as "Home".
