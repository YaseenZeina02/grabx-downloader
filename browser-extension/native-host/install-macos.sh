#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 EXTENSION_ID /absolute/path/to/grabx-native-host" >&2
  exit 2
fi

extension_id=$1
host_path=$2
case "$extension_id" in
  *[!a-p]*|'') echo "Invalid Chrome extension ID" >&2; exit 2 ;;
esac
case "$host_path" in
  /*) ;;
  *) echo "Native host path must be absolute" >&2; exit 2 ;;
esac
if [ ! -x "$host_path" ]; then
  echo "Native host is not executable: $host_path" >&2
  exit 2
fi

target_dir="$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts"
target_file="$target_dir/com.grabx.browser_bridge.json"
mkdir -p "$target_dir"
escaped_path=$(printf '%s' "$host_path" | sed 's/[\\&|]/\\&/g')
escaped_id=$(printf '%s' "$extension_id" | sed 's/[\\&|]/\\&/g')
sed -e "s|__NATIVE_HOST_PATH__|$escaped_path|g" \
    -e "s|__EXTENSION_ID__|$escaped_id|g" \
    "$(dirname "$0")/com.grabx.browser_bridge.chrome.json.template" > "$target_file"
chmod 600 "$target_file"
echo "Installed: $target_file"
