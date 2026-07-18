# Deploying Sonos Lyrics on Proxmox (LXC)

This guide runs the app as an always-on service in a lightweight Debian LXC
container on Proxmox. A container is preferred over a laptop/desktop because it
never sleeps and auto-starts with the host.

> **Important — Sonos discovery requires the same network.** Sonos uses SSDP
> multicast, which does not cross subnets/VLANs. The container's bridge
> (e.g. `vmbr0`) **must be on the same L2 network/VLAN as your Sonos speakers**.
> If the speaker dropdown is empty, this is almost always the cause.

## 1. Create the container (on the Proxmox host)

```bash
# Get a Debian 12 template (check the exact name for your mirror first):
pveam update
pveam available --section system | grep debian-12
pveam download local debian-12-standard_12.12-1_amd64.tar.zst   # use the name from above

# Create + start. Adjust VMID, storage, and bridge to match your setup.
pct create 200 local:vztmpl/debian-12-standard_12.12-1_amd64.tar.zst \
  --hostname sonos-lyrics \
  --cores 1 --memory 512 --swap 256 \
  --rootfs local-lvm:4 \
  --net0 name=eth0,bridge=vmbr0,ip=dhcp \
  --unprivileged 1 --onboot 1 --password

pct start 200
pct enter 200
```

> For a stable address, set a **DHCP reservation** for the container's MAC on
> your router, or give it a static IP:
> `--net0 name=eth0,bridge=vmbr0,ip=192.168.x.y/24,gw=192.168.x.1`

## 2. Install Node + the app (inside the container)

```bash
apt update && apt install -y curl git ca-certificates
curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
apt install -y nodejs
node -v   # expect v22.x

git clone https://github.com/nwokolo/sonos-lyrics.git /opt/sonos-lyrics
cd /opt/sonos-lyrics
npm install --omit=dev
```

## 3. Install the systemd service

```bash
cp /opt/sonos-lyrics/deploy/sonos-lyrics.service /etc/systemd/system/sonos-lyrics.service
systemctl daemon-reload
systemctl enable --now sonos-lyrics
systemctl status sonos-lyrics --no-pager
```

The service auto-starts on boot and restarts on crash. Set `PORT` in the unit
file if you need a different port.

## 4. Verify

- Open `http://<container-ip>:3000` (or `http://sonos-lyrics:3000`).
- Confirm the speaker dropdown populates (proves Sonos discovery works).

## Updating later

```bash
cd /opt/sonos-lyrics && git pull && npm install --omit=dev && systemctl restart sonos-lyrics
```

## Logs / troubleshooting

```bash
journalctl -u sonos-lyrics -f          # live logs
systemctl restart sonos-lyrics          # restart
```

- **Empty speaker dropdown** → container is on the wrong bridge/VLAN for Sonos
  SSDP multicast. Fix `--net0 bridge=` to match the speakers' network.
- **Speakers vanish after a container restart** (discovery worked before, now
  "no speakers found" even after clicking ↻) → multicast/SSDP is being dropped,
  typically **IGMP snooping** on the bridge after the container re-joined it.
  Two fixes:
  1. **Recommended — bypass multicast entirely.** Set `SONOS_HOSTS` to your
     speakers' IPs (comma-separated) so the app talks to them directly over
     unicast. Give the speakers DHCP reservations so the IPs stay put. Add to
     the systemd unit:
     ```ini
     Environment=SONOS_HOSTS=192.168.86.40,192.168.86.41
     ```
     then `systemctl daemon-reload && systemctl restart sonos-lyrics`.
     Find speaker IPs in the Sonos app (Settings → System → About My System) or
     your router's DHCP table.
  2. **Or fix multicast on the host** — disable bridge IGMP snooping (or add an
     IGMP querier): `echo 0 > /sys/class/net/vmbr0/bridge/multicast_snooping`
     (persist via your network config).
- **Slow first lyrics load** → lrclib.net is geographically distant; the app
  caches per song, so subsequent loads are instant.
