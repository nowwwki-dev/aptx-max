#!/system/bin/sh
DEX=/data/local/tmp/set-a2dp-codec.dex

if [ ! -f "$DEX" ]; then
  echo "Missing $DEX"
  echo "Run setup from a computer once to push the helper dex."
  exit 1
fi

CLASSPATH="$DEX" app_process /system/bin SetA2dpCodec

dumpsys bluetooth_manager | grep -E "Current Codec|mCodecConfig" | head -n 5
