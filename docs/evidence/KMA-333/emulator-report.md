# Emulator evidence

KVM is present (`/dev/kvm`). The emulator user was not in group 103, so the node was opened with mode 666 for this session. That is a local permission change, not a missing hypervisor.

Booted `kma333`, Pixel 6, `system-images;android-34;google_apis;x86_64`, `-accel on -no-window`. Log: `Boot completed in 38477 ms`.

Installed `app/build/outputs/apk/debug/app-x86_64-debug.apk`.
Package: `com.xdutsuay.chidori.nagasa.debug` (this tree's debug suffix is `.debug`, not `.dev`).

What was exercised:

- Settings shows a Check-ins row. Screenshot: `settings.png`.
- Check-ins opens with the enable switch on (seeded for the run), Daily, 2h, quiet hours, pause off, and an empty Open items list. Screenshot: `check-ins.png`.
- After inserting one fresh `WAITING_ON_USER` row, Open items shows "Ship the migration" with Park and Close. Screenshot: `open-items.png`.
- The unique work `proactive-checkin` was scheduled with `batteryNotLow=true` and a 24h interval (job `androidx.work.systemjobscheduler` id 3, then 4).
- WorkManager refuses a force-run that is earlier than its own schedule (`Delaying execution for CheckInWorker because it is being executed before schedule`). Backdating `WorkSpec.last_enqueue_time` and running `adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler com.xdutsuay.chidori.nagasa.debug 4` with the app in the background executed the worker.

```
WM-WorkerWrapper: Starting work for com.druk.lmplayground.checkin.CheckInWorker
WM-WorkerWrapper: Worker result SUCCESS for Work [ id=2397f472-d876-4f5e-a15a-bc0aa00b4cf8, tags={ com.druk.lmplayground.checkin.CheckInWorker, proactive-checkin } ]
```

The shade then showed one notification, title Check-in, body "• Ship the migration", actions Done, Park, Later. Screenshot: `notification.png`. The row became `RAISED` with `fresh=0` and `lastRaisedAt` set.

Quiet hours were set to 00:00–00:00 for that run so the gate would not stop a 07:38 device clock. Inactivity was 0 for the same reason. Those values were written into the app's preferences on the emulator; they are not the product defaults (22:00–08:00 and 2 hours).
