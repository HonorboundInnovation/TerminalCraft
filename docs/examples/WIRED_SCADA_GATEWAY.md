# Query SCADA through a wired rack gateway

First configure a sensor tag, such as `plant.signal`, using `SCADA_SENSOR_DASHBOARD.md`.

Place a Server Rack beside a modem. Connect that modem by same-color supported Network Cable to a second modem beside the remote terminal. Set both modems to wired mode using sneak-use; check the displayed mode. Keep each terminal adjacent to only its intended modem. The gateway modem must have exactly one adjacent rack.

From a terminal beside the gateway modem:

```text
modem open 42
modem scada add plant-scada 42
```

From the remote terminal:

```text
modem open 41
modem services
modem scada request plant-scada read plant.signal 1 41
```

Allow the server to process the request, then run `modem recv 8`. The reply includes the tag name, value, unit, quality and sample time. A removed sensor may retain its last number with `offline` quality; it is not a fresh measurement. Cutting the cable makes a new request fail as unreachable.

The gateway is deliberately read-only and publishes the world's SCADA telemetry to reachable clients. It does not give clients permission to control equipment, change roles or acknowledge alarms. Use physical/logical network isolation when that telemetry should not be shared with every reachable endpoint. These setup commands create an advertised service; choose a unique service name for an existing plant.
