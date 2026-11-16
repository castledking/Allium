# Required fixed-artifact regression sweep

Retain every suite in the accepted 20-suite baseline in RESULTS.md and add the
S21 real-client sign lane on **both Spigot 1.20.1 and Paper 26.3**. The required
sweep is now 22 suites. Historical passes do not stand in for the current jar.
All lanes must name the same immutable jar and SHA256; preserve their manifests,
harness freezes, failed attempts and cleanup results. Never run against production.

The sign lanes remain implemented by `tests/live/economy/run.py --signs`. Run
both sequentially with the owned port lock:

```
tools/run-sign-regression --allium-jar /absolute/frozen/Allium.jar \
  --sha256 SHA256 --run-prefix regression-REVISION \
  --run-root /mnt/storage/.allium-validation/YYYYMMDD/s20-economy/runs
```

Infrastructure arguments pass through to the existing harness. The wrapper fails
on either failed lane and never changes the frozen artifact. Keep service-level
SignServiceMatrixTest in the normal Gradle check as a second regression layer;
its mocks do not replace native event, packet, tile persistence or provider gates.

S23 adds native chat/nickname/channel/deletion/alias gates on both server lanes
(`tools/run-chat-regression` with the same arguments), taking the required full
sweep to 24 suites. Its fixture relay never contacts Discord. Optional PAPI ABI
lanes additionally exercise cached GradientPlus palette handling and do not
replace validation of the operator's actual PAPI/GradientPlus/Discord/packet peer
versions. The S23 report distinguishes the gates rerun for this change from the
full 24-suite sweep; neither historical results nor fixture peers imply a new
full-sweep pass.
