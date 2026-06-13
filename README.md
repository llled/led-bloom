# LED Bloom for WLED

Do you want to run an arbitrary number of WLED devices without needing to register them in your LED software? Or clone a single WLED device pattern to any number of WLED devices? LED Bloom automatically discovers WLED devices on your network, receives a single stream of pixels and fans them out to all of the devices. 

You point any [DDP](http://www.3waylabs.com/ddp/) source (xLights, WLED Sync, a custom renderer, etc.) at this service. It discovers WLED devices on your LAN, learns their matrix dimensions, places each one at a random position inside a virtual master canvas, then slices each frame and forwards the corresponding pixels to each device over DDP.

Built to power the syncing of wearable  LED costume pieces for Lava Lounge events. As each new wearable comes into wifi range, this software picks it up, registers it, and starts sending, leading to really fun synced effects. 

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
| `ledbloom.skip-ips` | `[]` | IPs to ignore during discovery. Auto-extended at runtime: any IP we receive DDP frames from is added here (and removed from the device registry) so a source is never treated as a forwarding target. |
| `logging.level.org.llled.ledbloom` | `DEBUG` | App log level |

## Sending pixels (configuring a DDP source)

LED Bloom is just a [DDP](http://www.3waylabs.com/ddp/) receiver. You point any DDP-capable
renderer at it and it fans the frames out to your WLED devices — so from the source's point of
view, LED Bloom looks like one big virtual matrix. There's nothing to install on the source
side; you only need to tell it where to send packets.

Aim your source at:

- **Host** — the machine running LED Bloom (its LAN IP, or `led-bloom.local`).
- **Port** — `4048` (the WLED/DDP default; matches `ledbloom.ddp-listen-port`).
- **Resolution** — match the master canvas: `frame-width × frame-height` (default `64 × 48`).
  That's `width × height` pixels = `width × height × 3` RGB channels (default `3072` px /
  `9216` channels). LED Bloom slices this canvas across your devices, so the source should
  render to these dimensions, not to any individual device's size.

### xLights

In xLights, LED Bloom is added as a single **Ethernet** controller speaking **DDP**
([xLights Ethernet controller setup](https://manual.xlights.org/xlights/chapters/chapter-four-set-up/lighting-networks/ethernet-controller)):

1. **Setup → Add Ethernet**, then select the new controller row.
2. Set **IP Address** to the LED Bloom host (LAN IP or `led-bloom.local`).
3. Set **Protocol** to **DDP**.
4. Leave **Channels Per Packet** at `1440` and enable **Keep Channels Per Packet**.
5. Set **Channels** to cover the master canvas (`frame-width × frame-height × 3`; `9216` for
   the defaults), then lay out a matrix/model of `frame-width × frame-height` against it.

### WLED Sync, FPP, custom renderers

Any other DDP source works the same way — point it at the LED Bloom host on port `4048` and
have it stream `frame-width × frame-height` RGB frames. See the
[WLED DDP documentation](https://kno.wled.ge/advanced/ddp/) for background on the protocol and
WLED's DDP support. A minimal custom sender just needs to emit standard DDP packets (RGB,
pixel-data destination) at your target framerate.

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

## Future TODOs/Ideas
* Test on Raspberry Pi, both for core functionality and scale.
* Ability to remove device via API and web interface in case something shouldn't get traffic and you don't want to restart.
* Create better distribution with config file on CLI for easier running. 
* Visual display of where each device is in the grid. 