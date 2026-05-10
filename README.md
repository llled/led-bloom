# WLED Multiplexer

A Spring Boot service that receives a single [DDP](http://www.3waylabs.com/ddp/) frame stream and forwards mapped sub-regions of it to many [WLED](https://kno.wled.ge/) matrices discovered on the local network.

You point any DDP source (xLights, WLED Sync, a custom renderer, etc.) at this service. It discovers WLED devices on your LAN, learns their matrix dimensions, places each one at a random position inside a virtual master canvas, then slices each frame and forwards the corresponding pixels to each device over DDP.

## How it works

```
   DDP source
       │  (one stream of WxH frames)
       ▼
┌──────────────────────┐         ┌──────────────────────┐
│  DdpFrameReceiver    │         │  WledDiscoveryRunner │
│  (UDP :4048)         │         │  mDNS + IP scan      │
└──────────┬───────────┘         └──────────┬───────────┘
           │ frames                         │ devices
           ▼                                ▼
        ┌──────────────────────────────────────┐
        │  DdpForwarder                        │
        │  per-device DeviceMapping slices     │
        │  the frame and sends DDP packets     │
        └──────────────────────────────────────┘
                       │
                       ▼
              WLED matrix #1, #2, #3, ...
```

- **Receiver** — listens for DDP on `multiplexer.ddp-listen-port` (default `4048`) for `frame-width × frame-height` RGB frames.
- **Discovery** — runs every `discovery-interval-seconds` (default `60s`). Combines mDNS (`_wled._tcp`) with a `/24` IP-range probe. WLED HTTP APIs (`/json/info`, `/ledmap.json`) are queried for matrix dimensions; if those are absent, dimensions are inferred from LED count.
- **Mapping** — each newly seen device is placed at a random `(translateX, translateY)` inside the master frame. Pixel offsets are pre-computed once per device for fast slicing.
- **Forwarder** — for every received frame, each device gets its sub-region forwarded as a DDP packet to its own IP.

## Requirements

- Java 17+
- Gradle (the wrapper is included)
- Network reachability to your WLED devices (UDP 4048, HTTP 80)

## Build & run

```sh
./gradlew bootRun
```

Or build a fat jar:

```sh
./gradlew bootJar
java -jar build/libs/WLED-multiplexer-0.1.0.jar
```

The HTTP API listens on `:8901`, the DDP receiver on `:4048` (defaults).

## Configuration

Edit `src/main/resources/application.yaml`, override with `--key=value` CLI flags, or set env vars (`MULTIPLEXER_FRAME_WIDTH=128` etc.).

| Key | Default | Notes |
|---|---|---|
| `server.port` | `8901` | REST API port |
| `multiplexer.ddp-listen-port` | `4048` | UDP port for incoming DDP |
| `multiplexer.frame-width` | `64` | Master canvas width (pixels) |
| `multiplexer.frame-height` | `48` | Master canvas height (pixels) |
| `multiplexer.ip-block` | _auto_ | E.g. `192.168.1.` — overrides auto-detection |
| `multiplexer.discovery-interval-seconds` | `60` | How often to rescan |
| `multiplexer.device-timeout-minutes` | `5` | Devices not seen for this long are purged |
| `multiplexer.skip-ips` | `[]` | IPs to ignore during discovery |
| `logging.level.org.llled.wledmux` | `DEBUG` | App log level |

## REST API

Base path: `/api/v1`

| Method | Path | Description |
|---|---|---|
| `GET` | `/status` | Uptime, frame counts, error counts, device counts |
| `GET` | `/devices` | All discovered devices with their mapping |
| `GET` | `/devices/{ip}` | One device by IP |
| `POST` | `/discovery/trigger` | Force an immediate discovery sweep |

Example:

```sh
curl http://localhost:8901/api/v1/status
curl http://localhost:8901/api/v1/devices
curl -X POST http://localhost:8901/api/v1/discovery/trigger
```

## Notes

- Devices larger than the master frame are clamped (and a warning is logged); consider raising `frame-width`/`frame-height`.
- Mapping positions are randomized on registration, so a restart re-shuffles where each device pulls its pixels from.
- mDNS discovery runs for ~5s per cycle; the IP-range probe walks `.2`–`.254` of the detected `/24` and pings each address with a 500 ms timeout.