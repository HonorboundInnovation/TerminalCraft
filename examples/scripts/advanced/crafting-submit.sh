#!/bin/bash
# Submit or replay one idempotent crafting request through a crafting-service device.
# The output contains the stable job UUID needed for later status/cancel calls.
# Usage: bash crafting-submit.sh <device-uuid> <operation-uuid> <resource-id> <amount>
if [ -z "$4" ]; then
  echo 'usage: crafting-submit.sh <device-uuid> <operation-uuid> <resource-id> <amount>'
else
  mkdir -p /home/player/jobs
  echo device=$1,operation_id=$2,resource=$3,amount=$4 > /home/player/jobs/crafting-$2.txt
  device call "$1" crafting.metadata >> /home/player/jobs/crafting-$2.txt
  if device call "$1" crafting.submit "$2" "$3" "$4" >> /home/player/jobs/crafting-$2.txt; then
    echo submission=accepted_or_replayed >> /home/player/jobs/crafting-$2.txt
  else
    echo submission=failed >> /home/player/jobs/crafting-$2.txt
  fi
  cat /home/player/jobs/crafting-$2.txt
  echo 'Use: device call <device-uuid> crafting.status <job-uuid>'
  echo 'Use: device call <device-uuid> crafting.cancel <job-uuid>'
fi
