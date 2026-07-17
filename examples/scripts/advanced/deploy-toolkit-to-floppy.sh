#!/bin/bash
# Deploy a curated automation toolkit to a mounted floppy and verify each source.
# Usage: bash deploy-toolkit-to-floppy.sh
if mount; then
  mkdir -p /disk/toolkit
  echo toolkit=terminalcraft-advanced > /disk/toolkit/MANIFEST.txt
  if [ -f /home/player/bin/safe-turtle-tunnel.sh ]; then
    cat /home/player/bin/safe-turtle-tunnel.sh | write /disk/toolkit/safe-turtle-tunnel.sh
    echo safe-turtle-tunnel.sh=installed >> /disk/toolkit/MANIFEST.txt
  else
    echo safe-turtle-tunnel.sh=missing >> /disk/toolkit/MANIFEST.txt
  fi
  if [ -f /home/player/bin/rednet-health-probe.sh ]; then
    cat /home/player/bin/rednet-health-probe.sh | write /disk/toolkit/rednet-health-probe.sh
    echo rednet-health-probe.sh=installed >> /disk/toolkit/MANIFEST.txt
  else
    echo rednet-health-probe.sh=missing >> /disk/toolkit/MANIFEST.txt
  fi
  if [ -f /home/player/bin/storage-audit-json.sh ]; then
    cat /home/player/bin/storage-audit-json.sh | write /disk/toolkit/storage-audit-json.sh
    echo storage-audit-json.sh=installed >> /disk/toolkit/MANIFEST.txt
  else
    echo storage-audit-json.sh=missing >> /disk/toolkit/MANIFEST.txt
  fi
  disk label terminalcraft-toolkit
  disk sync
  cat /disk/toolkit/MANIFEST.txt
  umount
else
  echo 'deployment failed: no mountable adjacent floppy'
fi
