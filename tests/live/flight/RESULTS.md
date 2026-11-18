# Flight and teleport safety

`3010c05`: +3 units, 929 → 932. Both SQLite native lanes pass 50/50 on
SHA256 `515af938119228b76e24573fcdc1263c8f6c83fa8fb0ad05243c6fc3be90da5d`.
This includes grounded and midair /there → /back, non-flyer safe refusal, and
explicit default-false unsafe permission admission. World bounds/chunks and
hazard behavior have SDK regression coverage. The channel acknowledgement now
uses its colored language key only after storage commits.

Flight restoration: +4 units, 932 → 936; check/shadowJar passes. Native artifact
SHA256 `b706f29aaeaa893dfb269b90c672b35bee89dbfdfdc5e801244269496310085d`.
The Spigot 1.20.1 fixture passes 55/55, including real midair disconnect/reconnect,
safe-login flight, slowfall preference persistence, alias handling and completion.
Paper 26.3 passes 55/55 on the same artifact and fixture. Evidence is retained under
`/mnt/storage/.allium-validation/20261117/s18-playerinfo/runs/flight-restoration-{spigot,paper}`.
No production processes or default port were used.
