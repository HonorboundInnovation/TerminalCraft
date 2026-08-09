# TerminalCraft 1.0.34

TerminalCraft 1.0.34 corrects a remaining placement artifact in the individually modeled electronics.

## Correction

The full-size electronics blocks now include a full opaque bottom plinth from the bottom of the
block to Y=1. This closes the lower-layer gap caused by the inset silhouettes and prevents the block
underneath from showing through. Cable-routing core and arm models remain open intentionally.

## Verification

- `./gradlew build --no-daemon`
- JSON/resource validation
- JAR archive integrity validation
- `git diff --check`

## Verification artifact

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.34.jar`
- Size: `1,447,553` bytes
- SHA-256: `8d78263e56b2fda9cc86b4ad7ac2293ada81e6f8de8b2a2604612487f8f5485a`
