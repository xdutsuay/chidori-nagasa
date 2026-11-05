# CP4.5 — GL-less Xvfb (size cut)

Default desktop install ≈ **114 MiB download / 304 MiB on disk**; ~204 MiB is mesa/LLVM for GLX.

**Decision for CP4:** keep the GL desktop pins. If a custom Xvfb-without-GL package is
ready (verified start + screenshot on Realme, apk pin list updated), land it as **CP4.5**
on a branch off CP4 tip — do not half-ship a broken GL-less binary in CP4.
