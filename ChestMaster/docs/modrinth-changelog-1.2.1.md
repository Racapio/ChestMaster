# 1.2.1

**Supported: MC 26.1–26.1.2 and 26.2 (Fabric).**

## Fixed
- **Hypixel menus no longer land in the database.** The scanner now indexes only containers whose title exactly matches a vanilla storage name (Chest / Large Chest / Barrel / Shulker Box, in your client language). Every server menu — Loadouts, the "At what price are you selling?" auction dialog, Sack of Sacks, Storage, backpacks, etc. — is a custom-titled fake chest and is now reliably skipped, even when opened while facing a real chest.
- Startup DB cleanup extended to these menu titles, so junk rows saved by earlier versions are removed automatically.

> Note: chests you renamed with a name tag are also skipped now (their title isn't a vanilla name). This is intentional — it's the only reliable way to keep Hypixel menus out.
