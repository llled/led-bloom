# LED Bloom

Do you want to run an arbitrary number of WLED devices without needing to register them in your LED software? Or clone a single WLED device pattern to any number of WLED devices? LED Bloom automatically discovers WLED devices on your network, receives a single stream of pixels and fans them out to all of the devices. 

You point any [DDP](http://www.3waylabs.com/ddp/) source (xLights, WLED Sync, a custom renderer, etc.) at this service. It discovers WLED devices on your LAN, learns their matrix dimensions, places each one at a random position inside a virtual master canvas, then slices each frame and forwards the corresponding pixels to each device over DDP.

Built to power the syncing of wearable  LED costume pieces for the Lava Lounge camp at Burning Flipside. As each new wearable comes into wifi range, this software picks it up, registers it, and starts sending, leading to really fun synced effects. 

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

- **Receiver** — listens for DDP on `ledbloom.ddp-listen-port` (default `4048`) for `frame-width × frame-height` RGB frames.
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
java -jar build/libs/LED-Bloom-0.1.0.jar
```

The HTTP Web interface and API listen on `:8901`, the DDP receiver on `:4048` (defaults). The status web page at http://led-bloom.local:8901/ will list the active config parameters and discovered devices.

## Configuration

Edit `src/main/resources/application.yaml`, override with `--key=value` CLI flags, or set env vars (`LEDBLOOM_FRAME_WIDTH=128` etc.).

| Key | Default | Notes |
|---|---|---|
| `server.port` | `8901` | REST API port |
| `ledbloom.ddp-listen-port` | `4048` | UDP port for incoming DDP |
| `ledbloom.frame-width` | `64` | Master canvas width (pixels) |
| `ledbloom.frame-height` | `48` | Master canvas height (pixels) |
| `ledbloom.ip-block` | _auto_ | E.g. `192.168.1.` — overrides auto-detection |
| `ledbloom.discovery-interval-seconds` | `60` | How often to rescan |
| `ledbloom.device-timeout-minutes` | `5` | Devices not seen for this long are purged |
| `ledbloom.skip-ips` | `[]` | IPs to ignore during discovery |
| `logging.level.org.llled.ledbloom` | `DEBUG` | App log level |

## REST API

Base path: `/api/v1`

| Method | Path | Description |
|---|---|---|
| `GET` | `/status` | Uptime, frame counts, error counts, device counts, and forwarder throughput metrics |
| `GET` | `/devices` | All devices with their mapping |
| `GET` | `/devices/{id}` | One device by `id` (`ip:port`) or bare `ip` |
| `POST` | `/devices` | Manually add a device (bypasses discovery); body `{ip, port?, name?, ledCount, width, height}` |
| `DELETE` | `/devices/{id}` | Remove one device by `id` (`ip:port`) or bare `ip` |
| `DELETE` | `/devices` | Remove all manually-added (pinned) devices |
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

## Scale testing

A load-test harness lives in `org.llled.ledbloom.loadtest`. It measures how many devices
LED Bloom can push to at a target framerate. Typical setup: run LED Bloom **and** the
sender on one machine (the sender → ingress hop is loopback) and the virtual receiver on a
second machine; all N virtual devices point at the receiver's IP across a port range, so only
the real egress fan-out crosses the LAN.

Run LED Bloom with discovery neutralized so it doesn't add noise:

```sh
./gradlew bootRun --args='--spring.profiles.active=loadtest'
```

On the receiver machine, bind a range of ports (one per virtual device):

```sh
./gradlew runVirtualReceiver "-Dvr.basePort=5000" "-Dvr.count=100"
```

On the LED Bloom machine, register the devices and stream frames at the target fps:

```sh
./gradlew runLoadTest "-Dlt.receiverHost=<receiver-ip>" "-Dlt.devices=200" "-Dlt.baseEgressPort=5000" "-Dlt.fps=60" "-Dlt.durationSeconds=30" "-Dlt.masterW=64" "-Dlt.masterH=48" "-Dlt.devW=16" "-Dlt.devH=16"
```

The `-D` arguments are quoted because PowerShell otherwise mangles an unquoted token that
starts with `-` and contains `.`/`=` (Gradle then reports `Task '.xxx' not found`). The quotes
are harmless in bash/sh too.

Watch the ceiling on `GET /api/v1/status`: `framesPerSecond` should hold at the target,
`packetsPerSecond` ≈ `framesPerSecond × activeForwarders`, and `fanoutMicrosAvg` rising toward
`frameIntervalMicrosAt60` (16667 µs) is the leading indicator that the single-threaded fan-out
is saturated. The receiver logs aggregate + per-port FPS so you can spot stragglers. Step
`lt.devices`/`vr.count` up until one of those breaks. The runner cleans up its devices on exit
(`-Dlt.cleanup=false` to leave them). See `org.llled.ledbloom.loadtest` for all `-Dvr.*`/`-Dlt.*` options.
