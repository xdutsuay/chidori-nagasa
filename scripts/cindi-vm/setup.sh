#!/usr/bin/env bash
# One-time VM setup for CINDI "Other computers" (run with sudo on a Debian/Ubuntu VM).
# Creates an unprivileged user (no sudo), installs a minimal X stack and the
# cindi-desktop helper. Nothing new listens on the network.
#   sudo bash setup.sh [username]        default username: cindi
# Then paste the key from CINDI (Other computers -> Copy key) into
#   /home/<username>/.ssh/authorized_keys
set -euo pipefail

USER_NAME="${1:-cindi}"
HERE="$(cd "$(dirname "$0")" && pwd)"

command -v apt-get >/dev/null || { echo "this script supports Debian/Ubuntu (apt) only" >&2; exit 1; }
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq --no-install-recommends xvfb openbox xdotool x11-utils imagemagick xterm >/dev/null

if ! id "$USER_NAME" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$USER_NAME"
fi
install -d -m 700 -o "$USER_NAME" -g "$USER_NAME" "/home/$USER_NAME/.ssh"
touch "/home/$USER_NAME/.ssh/authorized_keys"
chown "$USER_NAME:$USER_NAME" "/home/$USER_NAME/.ssh/authorized_keys"
chmod 600 "/home/$USER_NAME/.ssh/authorized_keys"

install -m 755 "$HERE/cindi-desktop" /usr/local/bin/cindi-desktop
echo "ready: user=$USER_NAME helper=/usr/local/bin/cindi-desktop"
