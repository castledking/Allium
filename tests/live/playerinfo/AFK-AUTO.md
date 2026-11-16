# Automatic AFK permission

`allium.afk.auto` defaults true and gates only the automatic inactivity transition.
Manual `/afk` retains its existing permission. The timer rechecks the permission at
each admission; denying it does not clear an existing manual/automatic AFK state,
and granting it permits admission at the next timer tick if already idle.

Unit delta **+2**, 833 → **835**, full check/shadowJar zero failures/errors/skips.
Two service regressions cover live permission changes and manual AFK while auto
is denied; the existing metadata test also checks the Bukkit default is true.
Native Spigot 1.20.1 and Paper 26.3, SQLite only: **35/35 each**, including manual
AFK with auto denied, idle denial, grant/re-evaluation and the existing sleep,
vanish, kick exemption and restart checks.

Frozen jar SHA256 `2d1659e17bc34cc967a040d34ce89a411d266c0e73931c3287479584ad7be9bd`.

- spigot: `/mnt/storage/.allium-validation/20261116/s18-playerinfo/runs/afk-auto-spigot/manifest.json` (SHA256 `d43077a79718dd4f5e4b41032ac5745e47dc73e2783ec30007c607142e8b3df8`)
- paper: `/mnt/storage/.allium-validation/20261116/s18-playerinfo/runs/afk-auto-paper/manifest.json` (SHA256 `467ad9179ddaa3a29d00a1f83123bb0821144db56a8aca9ee04dbf17791658d6`)
