#!/bin/sh
set -eu
cd "$(dirname "$0")"

install -d -m 0755 /opt/ages-of-siege
install -m 0644 relay.mjs lobby.mjs /opt/ages-of-siege/
install -d -m 0700 /etc/ages-of-siege
env_file=/etc/ages-of-siege/relay.env
umask 077
touch "$env_file"
chmod 0600 "$env_file"
for key_name in AGES_OF_SIEGE_HOST_KEY AGES_OF_SIEGE_LOBBY_KEY; do
    if ! grep -q "^${key_name}=" "$env_file"; then
        key=$(node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("hex"))')
        printf '%s=%s\n' "$key_name" "$key" >> "$env_file"
    fi
done

install -m 0644 ages-of-siege-relay.service /etc/systemd/system/ages-of-siege-relay.service
systemctl daemon-reload
systemctl enable ages-of-siege-relay.service
systemctl restart ages-of-siege-relay.service
systemctl is-active ages-of-siege-relay.service
