#!/usr/bin/env bash
# One-time (and re-runnable) setup of a fresh Ubuntu 24.04 droplet for There.
# Run as root: `make provision`. Safe to run again; every step checks first.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
SRC=/tmp/there-deploy

echo "== swap (1 GB; DigitalOcean droplets have none)"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 1G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
echo 'vm.swappiness=10' > /etc/sysctl.d/99-there.conf
sysctl -q --system

echo "== journald: cap logs at 200 MB"
mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]\nSystemMaxUse=200M\n' > /etc/systemd/journald.conf.d/there.conf
systemctl restart systemd-journald

echo "== packages and automatic security updates"
apt-get update -q
apt-get upgrade -yq
apt-get install -yq ufw unattended-upgrades sqlite3 curl gpg debian-keyring debian-archive-keyring apt-transport-https

echo "== firewall: SSH, HTTP, HTTPS (incl. HTTP/3)"
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw --force enable

echo "== SSH: keys only"
cat > /etc/ssh/sshd_config.d/10-there.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
EOF
systemctl reload ssh

echo "== Caddy (official apt repo)"
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' -o /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -q
  apt-get install -yq caddy
fi

echo "== service user and folders"
id there >/dev/null 2>&1 || useradd --system --home-dir /var/lib/there --shell /usr/sbin/nologin there
install -d -o root -g root -m 755 /opt/there /var/www/there
install -d -o there -g there -m 750 /var/lib/there
install -d -o root -g there -m 750 /etc/there

echo "== config files"
install -m 644 "$SRC/there.service" /etc/systemd/system/there.service
install -m 644 "$SRC/Caddyfile" /etc/caddy/Caddyfile
systemctl daemon-reload
systemctl enable there >/dev/null
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
systemctl reload caddy || systemctl restart caddy

echo "== done. Next: make deploy"
