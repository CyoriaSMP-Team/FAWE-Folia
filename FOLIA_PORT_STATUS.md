# FAWE-Folia port status — 2026-10-17

**WIP / NOT VALIDATED — DO NOT DEPLOY TO PRODUCTION.**

This is a direct fork of official IntellectualSites/FastAsyncWorldEdit. The `folia/1.21.11` branch contains a draft Folia/Paper-compatible scheduler bridge, region/entity ownership adaptations for the Paperweight 1.21.11 adapter, native chunk writes and regen, and adapter dispatch changes.

`folia-supported: true` has intentionally NOT been declared: there is no successful whole-project build nor real Folia multi-region/integration test, so declaring runtime compatibility would be premature.

Verification: `git diff --check` passed; targeted scheduler fallback compiled and passed a focused JUnit test in the coding pass. Full Gradle test startup was blocked in the coding sandbox by network-interface restrictions. No valid full Gradle compile, integration or performance test has been completed. The driver will not deploy this draft.

Unverified: cross-region operations, async NMS chunk access, entity retirement, chunk regeneration, history/undo consistency, and WorldGuard/ProtectionStones integration. FAWE replaces WorldEdit and must not be installed alongside standalone WorldEdit.

No auto-updates, auto-merge, or production deploy.
