#!/bin/sh
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "Usage: $0 EXTENSION_ID /absolute/path/to/grabx-native-host [chrome|brave]" >&2
  exit 2
fi

extension_id=$1
host_path=$2
browser=${3:-chrome}
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

# Browsers launched as macOS applications may be denied access to development
# builds kept under Documents/Desktop. Stage Gradle's complete application
# distribution in Application Support so the launcher and its lib directory
# remain together in a browser-accessible location.
host_bin_dir=$(dirname "$host_path")
distribution_dir=$(dirname "$host_bin_dir")
if [ -d "$distribution_dir/lib" ]; then
  staged_dir="$HOME/Library/Application Support/GrabX/native-host"
  mkdir -p "$staged_dir"
  ditto "$distribution_dir" "$staged_dir"
  host_path="$staged_dir/bin/$(basename "$host_path")"
  chmod 700 "$host_path"
  echo "Staged native host: $host_path"
fi

escaped_path=$(printf '%s' "$host_path" | sed 's/[\\&|]/\\&/g')
escaped_id=$(printf '%s' "$extension_id" | sed 's/[\\&|]/\\&/g')

install_manifest() {
  target_dir=$1
  target_file="$target_dir/com.grabx.browser_bridge.json"
  mkdir -p "$target_dir"
  sed -e "s|__NATIVE_HOST_PATH__|$escaped_path|g" \
      -e "s|__EXTENSION_ID__|$escaped_id|g" \
      "$(dirname "$0")/com.grabx.browser_bridge.chrome.json.template" > "$target_file"
  chmod 600 "$target_file"
  echo "Installed: $target_file"
}

case "$browser" in
  chrome)
    install_manifest "$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts"
    ;;
  brave)
    # Brave has used both its own directory and Chrome's compatibility directory
    # across releases. Installing the same restricted manifest in both is safe.
    install_manifest "$HOME/Library/Application Support/BraveSoftware/Brave-Browser/NativeMessagingHosts"
    install_manifest "$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts"
    ;;
  *)
    echo "Unsupported browser: $browser" >&2
    exit 2
    ;;
esac
