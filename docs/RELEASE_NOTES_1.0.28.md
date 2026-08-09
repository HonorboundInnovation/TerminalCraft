# TerminalCraft 1.0.28

TerminalCraft 1.0.28 completes the beginner-first RedNet networking pass. The existing bounded,
versioned packet transport and physical cable/router traversal now have automatic identity,
addressing, and setup behavior suitable for a first network, while manual controls remain available
for advanced installations.

## RedNet networking

- New modems automatically open channel 42 and receive stable `node-...` hostnames.
- Wired subnets and wireless pools receive bounded private-looking RedNet leases (`10.x.x.x`).
- A live Network Router is registered as the lease authority for attached physical subnets. Isolated
  links use a link-local fallback and do not require a router.
- Network Router identities persist through world saves and are registered server-side.
- `modem status` reports identity, transport, channels, lease source, router count, protocol framing,
  and pending traffic.
- `modem auto on|off` controls the automatic profile; explicit channels, names, logical networks,
  and router face policy remain supported.
- `modem send 'message'` and `modem sendto <host> 'message'` provide short beginner-friendly forms.

## Protocol and verification

RedNet channel and control protocol contracts are now explicit in the protocol catalog and status
output. The existing versioned envelope, bounded payload, hop limit, queue budgets, topology routing,
reliable delivery, and duplicate filtering remain authoritative.

Added headless coverage for automatic defaults and DHCP-equivalent address allocation. Full `clean
build` verification passes.

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.28.jar`
- Size: `1,311,868` bytes
- SHA-256: `773b9339f17d86ea2b3d1506df44239a775011d75bc61ee82499d9565120b910`
