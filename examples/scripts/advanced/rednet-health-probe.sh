#!/bin/bash
# Build a route, service, and reliable-probe incident report.
# The probe prints its message UUID; query it later with: modem delivery <uuid>
# Usage: bash rednet-health-probe.sh <host> <port> <reply-port>
if [ -z "$3" ]; then
  echo 'usage: rednet-health-probe.sh <host> <port> <reply-port>'
else
  mkdir -p /home/player/audits
  echo target=$1,port=$2,reply_port=$3 > /home/player/audits/rednet-health.txt
  modem interfaces >> /home/player/audits/rednet-health.txt
  modem neighbors 32 >> /home/player/audits/rednet-health.txt
  modem hosts >> /home/player/audits/rednet-health.txt
  modem services >> /home/player/audits/rednet-health.txt
  if modem route "$1" >> /home/player/audits/rednet-health.txt; then
    echo route=reachable >> /home/player/audits/rednet-health.txt
    modem open "$3" >> /home/player/audits/rednet-health.txt
    modem probe "$1" "$2" "$3" 'terminalcraft-health-check' >> /home/player/audits/rednet-health.txt
  else
    echo route=unreachable >> /home/player/audits/rednet-health.txt
  fi
  cat /home/player/audits/rednet-health.txt
fi
