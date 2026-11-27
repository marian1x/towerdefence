#!/bin/sh
set -eu
cd "$(dirname "$0")"

install -d -m 0755 /opt/ages-of-siege
install -m 0644 relay.mjs /opt/ages-of-siege/relay.mjs
install -d -m 0700 /etc/ages-of-siege
if [ ! -e /etc/ages-of-siege/relay.env ]; then
    key=$(node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("hex"))')
    (umask 077; printf 'AGES_OF_SIEGE_HOST_KEY=%s\n' "$key" > /etc/ages-of-siege/relay.env)
fi

install -m 0644 ages-of-siege-relay.service /etc/systemd/system/ages-of-siege-relay.service
systemctl daemon-reload
systemctl enable ages-of-siege-relay.service
systemctl restart ages-of-siege-relay.service
systemctl is-active ages-of-siege-relay.service
