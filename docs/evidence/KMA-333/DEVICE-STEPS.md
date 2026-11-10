# KMA-333 on-device steps

Debug application id from this tree is `com.xdutsuay.chidori.nagasa.debug`
(`applicationId` + `applicationIdSuffix = ".debug"` in `app/build.gradle.kts`).
The ticket text says `com.xdutsuay.chidori.nagasa.dev`. This repo has no `.dev`
suffix. Use `.debug` for the APK this branch builds. If a device build was
signed with a different suffix, substitute that package in the commands below.

Build and install (from the repo root, on a machine with the Android SDK):

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Open the app and allow notifications if Android asks.
2. Settings → Check-ins. Confirm **Enable check-ins** is off and **Pause background activity** is off.
3. Turn **Enable check-ins** on. Leave frequency on Daily, inactivity on 2h, quiet hours 22:00–08:00.
4. Seed a chat item. In a chat, send `remind me about the on-device check`.
5. Confirm Settings → Check-ins → Open items lists that reminder.
6. Force the worker. Check-ins only run when the app is not in the foreground and the battery is not low, so press Home first.

```bash
adb shell dumpsys jobscheduler | grep -A 20 proactive-checkin
# WorkManager's job id is in that dump. Then:
adb shell cmd jobscheduler run -f com.xdutsuay.chidori.nagasa.debug <JOB_ID>
```

If `cmd jobscheduler` cannot see the job yet, the periodic work is inexact. From a debug build you can also enqueue an immediate one-shot with the WorkManager test driver; the production worker class is `com.druk.lmplayground.checkin.CheckInWorker` and the unique name is `proactive-checkin`.

7. The shade should show one **Check-in** notification with Done, Park, and Later. Tap it: a chat item opens that session. A desktop item opens the run. A team-room item opens Settings → Check-ins.
8. Park from the notification, or from Open items. The row's status becomes PARKED and the next run must not mention it.
9. Turn **Pause background activity** on, background the app, and force the job again. Logcat should show the run stopping on the pause gate, with no `listRuns` from the check-in and no badge poll.

```bash
adb logcat -d -s CheckInWorker:W NeedsYouBadgePoller:D | tail -50
```

10. Turn check-ins off. `dumpsys jobscheduler` should no longer list `proactive-checkin` as a pending job for this package.
